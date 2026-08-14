package com.consilens.server.application.capability;

import com.consilens.connector.api.model.TablePath;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.api.dto.ArtifactRefDto;
import com.consilens.server.application.artifact.ArtifactService;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.application.capability.config.ServerCompareConfigService;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.InvalidInputException;
import com.consilens.server.application.capability.config.EndpointConfig;
import com.consilens.server.domain.model.DataSourceRecord;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import com.consilens.server.application.datasource.DialectSupport;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.support.crypto.CryptoSupport;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.NoopDiffLifecycle;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.sink.api.model.ResultConfig;
import com.consilens.sink.api.model.SinkConfig;
import com.consilens.sink.api.DefaultDiffLifecycle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RunTaskHandler implements CapabilityHandler<RunRequest> {

    private static final int MAX_DIFF_ROWS_IN_RESULT = 1000;

    private final ArtifactService artifactService;
    private final ServerCompareConfigService configService;
    private final TaskRepository taskRepository;
    private final DataSourceRepository dataSourceRepository;
    private final CryptoSupport cryptoSupport;
    private final DialectSupport dialectSupport;
    private static final String NAME_CHARS_PATTERN = "[A-Za-z0-9_.$: \\-]{1,256}";

    private final ConsilensServerProperties properties;
    private final ObjectMapper objectMapper;

    public RunTaskHandler(ArtifactService artifactService,
                          ServerCompareConfigService configService,
                          TaskRepository taskRepository,
                          DataSourceRepository dataSourceRepository,
                          CryptoSupport cryptoSupport,
                          DialectSupport dialectSupport,
                          ConsilensServerProperties properties,
                          ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.configService = configService;
        this.taskRepository = taskRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.cryptoSupport = cryptoSupport;
        this.dialectSupport = dialectSupport;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行端连接注入：定义运行/直传配置通过 source/target.datasourceId 引用数据源，
     * 此处按需读取并解密连接参数（提交落库的 payload 不含真实密码）。
     */
    void injectConnections(ServerCompareConfig config) {
        injectEndpoint(config.getSource());
        injectEndpoint(config.getTarget());
    }

    void injectEndpoint(EndpointConfig endpoint) {
        if (endpoint == null) {
            return;
        }
        Map<String, Object> connection = endpoint.getConnection();
        // 情况1：提交端加密存储的密码（enc: 前缀）→ 解密还原（外部直传场景）
        if (connection != null && connection.get("password") instanceof String
                && ((String) connection.get("password")).startsWith("enc:")) {
            Map<String, Object> fixed = new LinkedHashMap<>(connection);
            fixed.put("password", cryptoSupport.reveal((String) connection.get("password")));
            endpoint.setConnection(fixed);
            return;
        }
        // 情况2：connection 缺失且引用数据源 → 数据源注入
        boolean needsInjection = connection == null || connection.isEmpty();
        if (!needsInjection || endpoint.getDatasourceId() == null) {
            return;
        }
        dataSourceRepository.findById(endpoint.getDatasourceId()).ifPresentOrElse(ds -> {
            Map<String, Object> param = fromParamJson(ds.getParamJson());
            Map<String, Object> injected = new LinkedHashMap<>();
            String host = stringValue(param.get("host"));
            Integer port = integerValue(param.get("port"));
            // 与 CLI 配置一致：显式 database（向导保存）优先，其次数据源 param
            String database = endpoint.getDatabase() != null && !endpoint.getDatabase().isBlank()
                    ? endpoint.getDatabase()
                    : stringValue(param.get("database"));
            // database 直接拼入 JDBC URL，复用创建数据源时的名称白名单防 URL 参数注入
            if (database != null && !database.matches(NAME_CHARS_PATTERN)) {
                throw new IllegalArgumentException("database contains invalid characters");
            }
            // connector 执行端需要完整 JDBC URL，按数据源类型经 dialect 构建
            dialectSupport.find(ds.getType())
                    .ifPresent(dialect -> {
                        Map<String, Object> urlParams = new LinkedHashMap<>();
                        urlParams.put("host", host == null ? "" : host);
                        urlParams.put("port", port == null ? dialect.getDefaultPort() : port);
                        urlParams.put("database", database == null ? "" : database);
                        // 方言扩展参数（Oracle sid / schema / properties）透传，不覆盖已有键
                        for (String key : new String[]{"sid", "schema", "properties"}) {
                            Object value = param.get(key);
                            if (value != null && !String.valueOf(value).isBlank()) {
                                urlParams.putIfAbsent(key, value);
                            }
                        }
                        injected.put("url", dialect.buildJdbcUrl(urlParams));
                    });
            injected.put("host", host);
            injected.put("port", port);
            injected.put("database", database);
            injected.put("username", stringValue(param.get("username")));
            injected.put("password", cryptoSupport.reveal(
                    param.get("password") != null ? String.valueOf(param.get("password")) : null));
            endpoint.setConnection(injected);
        }, () -> org.slf4j.LoggerFactory.getLogger(RunTaskHandler.class)
                .warn("datasourceId {} referenced by config not found; connection left empty",
                        endpoint.getDatasourceId()));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Map<String, Object> fromParamJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    @Override
    public CapabilityType type() {
        return CapabilityType.RUN;
    }

    @Override
    public TaskExecutionResult handle(TaskExecutionContext context, RunRequest request) {
        Instant startedAt = Instant.now();
        try {
            ServerCompareConfig config = configService.fromRunRequest(request);
            injectConnections(config);
            if (Boolean.TRUE.equals(options(request).getDryRun())) {
                return writeSuccess(context, request, startedAt, true,
                        configService.validateContent(config), null);
            }
            CompareOutcome outcome = executeComparison(context, config, options(request));
            assertNotCancellationRequested(context);
            return writeSuccess(context, request, startedAt, false, runResult(outcome.diffResult), outcome);
        } catch (TaskCancellationException exception) {
            throw exception;
        } catch (InvalidInputException exception) {
            ArtifactRefDto artifact = writeFailure(context, request, startedAt, "INVALID_INPUT", exception.getMessage());
            throw new CapabilityExecutionException("INVALID_INPUT", exception.getMessage(), artifact.getId(), false, exception);
        } catch (Exception exception) {
            ArtifactRefDto artifact = writeFailure(context, request, startedAt, "RUN_EXECUTION_ERROR", exception.getMessage());
            throw new CapabilityExecutionException("RUN_EXECUTION_ERROR", exception.getMessage(), artifact.getId(), true, exception);
        }
    }

    /** 执行结果 + 差异 sink 写入状态（行数/截断） */
    static final class CompareOutcome {
        final DiffResult diffResult;
        final long writtenRecordCount;
        final boolean truncated;

        CompareOutcome(DiffResult diffResult, long writtenRecordCount, boolean truncated) {
            this.diffResult = diffResult;
            this.writtenRecordCount = writtenRecordCount;
            this.truncated = truncated;
        }
    }

    protected CompareOutcome executeComparison(TaskExecutionContext taskContext,
                                               ServerCompareConfig config,
                                               RunRequest.Options options) throws Exception {
        CompareRequest compareRequest = configService.toCompareRequest(config, options);
        DiffLifecycle lifecycle = buildLifecycle(config);
        DiffContext diffContext = buildDiffContext(taskContext, config);
        Throwable failure = null;
        try {
            assertNotCancellationRequested(taskContext);
            lifecycle.onDiffStart(diffContext);
            DiffResult diffResult = createCompareRuntime().execute(compareRequest);
            assertNotCancellationRequested(taskContext);
            publishDifferences(diffResult, lifecycle, diffContext);
            assertNotCancellationRequested(taskContext);
            lifecycle.onDiffComplete(diffResult, diffContext);
            return new CompareOutcome(diffResult,
                    lifecycle instanceof DefaultDiffLifecycle
                            ? ((DefaultDiffLifecycle) lifecycle).writtenRecordCount()
                            : 0L,
                    lifecycle instanceof DefaultDiffLifecycle
                            && ((DefaultDiffLifecycle) lifecycle).isTruncated());
        } catch (Exception exception) {
            failure = exception;
            try {
                lifecycle.onDiffError(diffContext, exception);
            } catch (Exception lifecycleException) {
                exception.addSuppressed(lifecycleException);
            }
            throw exception;
        } finally {
            try {
                lifecycle.close();
            } catch (Exception lifecycleException) {
                if (failure != null) {
                    failure.addSuppressed(lifecycleException);
                } else {
                    throw lifecycleException;
                }
            }
        }
    }

    protected CompareRuntime createCompareRuntime() {
        return new DefaultCompareRuntime();
    }

    protected DiffLifecycle buildLifecycle(ServerCompareConfig config) {
        ResultConfig resultConfig = config.getResult();
        if (resultConfig == null || resultConfig.getSinks() == null || resultConfig.getSinks().isEmpty()) {
            // 默认写入：差异明细经 jsonl sink 落盘（供分页读取，不截断）；统计数据由 writeSuccess 入库
            SinkConfig defaultSink = SinkConfig.builder()
                    .format("jsonl")
                    .type("diff-record")
                    .enabled(true)
                    .properties(toJson(Map.of(
                            "path", defaultDiffPath())))
                    .build();
            resultConfig = ResultConfig.builder()
                    .failOnSinkError(true)
                    .sinks(List.of(defaultSink))
                    .build();
        }
        return new DefaultDiffLifecycle(resultConfig);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("failed to serialize sink properties", exception);
        }
    }

    private String defaultDiffPath() {
        return properties.getArtifact().getLocalBaseDir() + "/run-result-${taskId}.jsonl";
    }

    private String resolvedDiffUri(String runId) {
        // 绝对路径 + 每次运行唯一 id，避免重试覆盖与 cwd 变化导致 URI 失效
        return Paths.get(properties.getArtifact().getLocalBaseDir(),
                "run-result-" + runId + ".jsonl").toAbsolutePath().toString();
    }

    private DiffContext buildDiffContext(TaskExecutionContext taskContext, ServerCompareConfig config) {
        return DiffContext.builder()
                .taskId(taskContext.getInstanceKey())
                .startTime(taskContext.getStartTime())
                .sourceTablePath(tablePath(config.getSource().getTable()))
                .targetTablePath(tablePath(config.getTarget().getTable()))
                .strategy(stringValue(config.getHints().get("strategy")))
                .algorithm(stringValue(config.getExecutionOptions().get("checksumAlgorithm")))
                .sourceColumnNames(columns(config))
                .targetColumnNames(columns(config))
                .build();
    }

    private TablePath tablePath(String table) {
        return table == null || table.isBlank() ? null : TablePath.fromString(table);
    }

    private List<String> columns(ServerCompareConfig config) {
        List<String> columns = new ArrayList<>(config.getKeys());
        if (config.getComparison() != null && config.getComparison().getFields() != null) {
            for (String field : config.getComparison().getFields()) {
                if (!columns.contains(field)) {
                    columns.add(field);
                }
            }
        }
        return columns;
    }

    private void publishDifferences(DiffResult result,
                                    DiffLifecycle lifecycle,
                                    DiffContext context) throws Exception {
        if (result != null && result.getDifferences() != null && !result.getDifferences().isEmpty()) {
            lifecycle.onDifferencesFound(result.getDifferences(), context);
        }
    }

    private void assertNotCancellationRequested(TaskExecutionContext context) {
        if (taskRepository == null || context == null || context.getTaskId() == null) {
            return;
        }
        boolean cancellationRequested = taskRepository.findById(context.getTaskId())
                .map(task -> task.getStatus() == TaskStatus.CANCEL_REQUESTED)
                .orElse(false);
        if (cancellationRequested) {
            throw new TaskCancellationException(context.getInstanceKey());
        }
    }

    private TaskExecutionResult writeSuccess(TaskExecutionContext context,
                                             RunRequest request,
                                             Instant startedAt,
                                             boolean dryRun,
                                             Map<String, Object> details,
                                             CompareOutcome outcome) {
        Map<String, Object> content = baseContent(request, startedAt);
        content.put("success", true);
        content.put("dryRun", dryRun);
        content.putAll(details);
        // 差异明细不再内联进 artifact：仅当默认 jsonl 文件确实落盘时才外置
        String diffFilePath = resolvedDiffUri(context.getInstanceKey());
        boolean diffFileWritten = outcome != null && outcome.writtenRecordCount > 0
                && Files.exists(Paths.get(diffFilePath));
        if (diffFileWritten) {
            content.remove("differences");
        }
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.RUN_RESULT,
                "json",
                content,
                Map.of("source", "run"));
        Map<String, Object> statistics = outcome != null && outcome.diffResult != null
                ? outcome.diffResult.getStatisticsMap()
                : Map.of();
        String differencesUri = diffFileWritten ? diffFilePath : null;
        artifactService.updateRunResultMeta(artifact.getId(),
                statistics,
                outcome != null && outcome.truncated,
                outcome != null ? outcome.writtenRecordCount : 0L,
                differencesUri);
        return CapabilityResultSupport.result(artifact);
    }

    private ArtifactRefDto writeFailure(TaskExecutionContext context,
                                        RunRequest request,
                                        Instant startedAt,
                                        String errorCode,
                                        String errorMessage) {
        Map<String, Object> content = baseContent(request, startedAt);
        content.put("success", false);
        content.put("dryRun", Boolean.TRUE.equals(options(request).getDryRun()));
        content.put("errorCode", errorCode);
        content.put("errorMessage", errorMessage);
        return artifactService.writeArtifact(context,
                ArtifactKind.RUN_RESULT,
                "json",
                content,
                Map.of("source", "run", "errorCode", errorCode));
    }

    private Map<String, Object> baseContent(RunRequest request, Instant startedAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("serialNo", request.getSerialNo());
        content.put("configArtifactId", request.getConfigArtifactId());
        content.put("startedAt", startedAt.toString());
        content.put("completedAt", Instant.now().toString());
        return content;
    }

    Map<String, Object> runResult(DiffResult diffResult) {
        Map<String, Object> content = new LinkedHashMap<>();
        boolean hasDifferences = diffResult != null && diffResult.hasDifferences();
        long differenceCount = diffResult != null ? diffResult.getDifferenceCount() : 0L;
        content.put("hasDifferences", hasDifferences);
        content.put("differenceCount", differenceCount);
        content.put("statistics", diffResult != null ? diffResult.getStatisticsMap() : Map.of());
        content.put("metadata", diffResult != null ? diffResult.getMetadata() : Map.of());
        List<DiffRow> allDifferences = diffResult != null && diffResult.getDifferences() != null
                ? diffResult.getDifferences()
                : List.of();
        // Keep the artifact bounded: persist the full statistics plus a sampled
        // detail list instead of serializing millions of diff rows into a 50MB cap.
        content.put("differenceSampleSize", (int) Math.min(differenceCount, MAX_DIFF_ROWS_IN_RESULT));
        content.put("differenceSampleTruncated", allDifferences.size() > MAX_DIFF_ROWS_IN_RESULT);
        content.put("differences", allDifferences.stream()
                .limit(MAX_DIFF_ROWS_IN_RESULT)
                .map(this::diffRow)
                .collect(Collectors.toList()));
        return content;
    }

    Map<String, Object> diffRow(DiffRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", row.getOperation().name());
        result.put("primaryKey", row.getPrimaryKey());
        result.put("metadata", row.getMetadata());
        return result;
    }

    private RunRequest.Options options(RunRequest request) {
        return request.getOptions() != null ? request.getOptions() : new RunRequest.Options();
    }
}
