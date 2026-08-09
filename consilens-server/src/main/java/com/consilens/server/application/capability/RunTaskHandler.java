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
import com.consilens.server.domain.model.TaskExecutionContext;
import com.consilens.server.domain.model.TaskExecutionResult;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import com.consilens.core.lifecycle.DiffContext;
import com.consilens.core.lifecycle.DiffLifecycle;
import com.consilens.core.lifecycle.NoopDiffLifecycle;
import com.consilens.sink.api.DefaultDiffLifecycle;
import com.consilens.sink.api.model.ResultConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class RunTaskHandler implements CapabilityHandler<RunRequest> {

    private static final int MAX_DIFF_ROWS_IN_RESULT = 1000;

    private final ArtifactService artifactService;
    private final ServerCompareConfigService configService;
    private final TaskRepository taskRepository;

    public RunTaskHandler(ArtifactService artifactService,
                          ServerCompareConfigService configService,
                          TaskRepository taskRepository) {
        this.artifactService = artifactService;
        this.configService = configService;
        this.taskRepository = taskRepository;
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
            if (Boolean.TRUE.equals(options(request).getDryRun())) {
                return writeSuccess(context, request, startedAt, true, configService.validateContent(config));
            }
            DiffResult diffResult = executeComparison(context, config, options(request));
            assertNotCancellationRequested(context);
            return writeSuccess(context, request, startedAt, false, runResult(diffResult));
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

    protected DiffResult executeComparison(TaskExecutionContext taskContext,
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
            return diffResult;
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
            return new NoopDiffLifecycle();
        }
        return new DefaultDiffLifecycle(resultConfig);
    }

    private DiffContext buildDiffContext(TaskExecutionContext taskContext, ServerCompareConfig config) {
        return DiffContext.builder()
                .taskId(taskContext.getTaskKey())
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
            throw new TaskCancellationException(context.getTaskKey());
        }
    }

    private TaskExecutionResult writeSuccess(TaskExecutionContext context,
                                             RunRequest request,
                                             Instant startedAt,
                                             boolean dryRun,
                                             Map<String, Object> details) {
        Map<String, Object> content = baseContent(request, startedAt);
        content.put("success", true);
        content.put("dryRun", dryRun);
        content.putAll(details);
        ArtifactRefDto artifact = artifactService.writeArtifact(context,
                ArtifactKind.RUN_RESULT,
                "json",
                content,
                Map.of("source", "run"));
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
