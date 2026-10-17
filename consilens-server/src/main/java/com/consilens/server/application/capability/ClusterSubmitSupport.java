package com.consilens.server.application.capability;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmissionContext;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 集群提交支撑：把 ServerCompareConfig 渲染为 CLI 兼容 descriptor，
 * 经 ClusterSubmitter SPI 提交到集群并等待终态。
 * 平台差异（execution mode、descriptor 引用方式、提交 spec）由各
 * ClusterSubmitter 实现负责；本类只保留通用骨架。
 */
@Component
public class ClusterSubmitSupport {

    private static final Logger log = LoggerFactory.getLogger(ClusterSubmitSupport.class);

    private static final long COMPLETION_POLL_SECONDS = 3;
    private static final String DEFAULT_STRATEGY_MODE = "join";

    private final ClusterSubmitterLocator submitterLocator;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public ClusterSubmitSupport(ClusterSubmitterLocator submitterLocator) {
        this.submitterLocator = submitterLocator;
    }

    public boolean isClusterPlatform(RunRequest.Options options) {
        return options != null && options.getPlatform() != null
                && !"local".equalsIgnoreCase(options.getPlatform().trim());
    }

    /** 提交并阻塞等待终态；轮询期间检测取消请求并 kill 集群应用。 */
    public ClusterApplicationResult submitAndAwait(TaskExecutionContext context,
                                                   RunRequest request,
                                                   ServerCompareConfig config,
                                                   CancelChecker cancelChecker) throws Exception {
        String platform = normalizedPlatform(request);
        ClusterSubmitter submitter = submitterLocator.locate(platform);
        String descriptor = yamlMapper.writeValueAsString(
                buildDescriptor(config, submitter.descriptorRedactsPasswords()));
        Path descriptorFile = submitter.requiresDescriptorFile() ? writeDescriptor(descriptor) : null;
        ClusterSubmission submission = null;
        boolean reachedTerminalState = false;
        try {
            // 平台提交请求（descriptor 引用、execution mode、平台 spec）由 submitter 构造
            ClusterSubmitRequest submitRequest = submitter.buildRequest(new ClusterSubmissionContext(
                    contextSubmissionId(request), descriptor, descriptorFile,
                    orEmpty(request.getOptions().getProperties())));
            submitRequest.validate();
            // 恢复重试场景：清扫同 submissionId 的残留集群应用（全新提交时为 no-op）
            try {
                submitter.killStale(submitRequest);
            } catch (RuntimeException e) {
                // 清扫失败不阻塞新提交；记录原因便于排查
                log.warn("stale cluster application sweep failed for submission {}; continuing with new submission",
                        submitRequest.getSubmissionId(), e);
            }
            submission = submitter.submit(submitRequest);
            ClusterApplicationResult result = awaitTerminal(context, request, submitter, submission, cancelChecker);
            reachedTerminalState = true;
            return result;
        } finally {
            if (submission != null && !reachedTerminalState) {
                try {
                    // 仅在提交后异常、超时或取消时终止，正常完成的 Job 不应被删除。
                    submitter.kill(submission);
                } catch (RuntimeException ignored) {
                    // 原始失败原因优先；终止为尽力而为。
                }
            }
            if (submitter instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) submitter).close();
                } catch (Exception ignored) {
                    // 提交结果已确定；清理失败不影响结果
                }
            }
            if (descriptorFile != null) {
                Files.deleteIfExists(descriptorFile);
            }
        }
    }

    private ClusterApplicationResult awaitTerminal(TaskExecutionContext context,
                                                   RunRequest request,
                                                   ClusterSubmitter submitter,
                                                   ClusterSubmission submission,
                                                   CancelChecker cancelChecker) throws Exception {
        Integer timeoutMs = request.getOptions().getTimeoutMs();
        long deadline = timeoutMs != null && timeoutMs > 0
                ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            if (cancelChecker.isCancellationRequested()) {
                throw new TaskCancellationException(context.getInstanceKey());
            }
            ClusterApplicationResult result = submitter.awaitCompletion(submission,
                    Duration.ofSeconds(COMPLETION_POLL_SECONDS));
            if (result != null) {
                return result;
            }
        }
        throw new java.util.concurrent.TimeoutException(
                "cluster application did not finish before timeout: " + submission.getClusterApplicationId());
    }

    private String normalizedPlatform(RunRequest request) {
        String platform = request.getOptions().getPlatform();
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("cluster platform is required");
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    private Path writeDescriptor(String descriptor) throws Exception {
        Path descriptorFile = Files.createTempFile("consilens-descriptor-", ".yaml");
        Files.writeString(descriptorFile, descriptor);
        return descriptorFile;
    }

    /** ServerCompareConfig → CLI 兼容 descriptor（与 examples/*.yaml 同构）。 */
    Map<String, Object> buildDescriptor(ServerCompareConfig config, boolean redactPasswords) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("source", endpointDescriptor(config.getSource(), "source", redactPasswords));
        descriptor.put("target", endpointDescriptor(config.getTarget(), "target", redactPasswords));

        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("source", keyColumns(config, true));
        keys.put("target", keyColumns(config, false));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("source", compareColumns(config, true));
        fields.put("target", compareColumns(config, false));
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("keys", keys);
        comparison.put("fields", fields);
        descriptor.put("comparison", comparison);

        Map<String, Object> execution = config.getExecutionOptions();
        Map<String, Object> strategy = new LinkedHashMap<>();
        // CLI 兼容默认 join；显式 executionOptions.mode 可覆盖（如 bisection）。
        Object mode = execution.get("mode");
        strategy.put("mode", mode == null || String.valueOf(mode).isBlank() ? DEFAULT_STRATEGY_MODE : mode);
        copyIfPresent(execution, "bisectionFactor", strategy);
        copyIfPresent(execution, "bisectionThreshold", strategy);
        copyIfPresent(execution, "batchSize", strategy);
        Object algorithm = execution.get("checksumAlgorithm");
        if (algorithm != null) {
            strategy.put("algorithm", algorithm);
        }
        Object localCompareMode = execution.get("localCompareMode");
        if (localCompareMode != null) {
            strategy.put("localCompare", Map.of("mode", localCompareMode));
        }
        descriptor.put("strategy", strategy);

        if (config.getResult() != null) {
            descriptor.put("result", config.getResult());
        }
        return descriptor;
    }

    private Map<String, Object> endpointDescriptor(com.consilens.server.application.capability.config.EndpointConfig endpoint,
                                                   String name,
                                                   boolean redactPasswords) {
        if (endpoint == null || endpoint.getConnection() == null || endpoint.getConnection().isEmpty()) {
            throw new IllegalArgumentException("endpoint connection is required for cluster submission: " + name);
        }
        Map<String, Object> connection = new LinkedHashMap<>(endpoint.getConnection());
        if (redactPasswords && connection.get("password") != null) {
            connection.put("password", "${env." + name.toUpperCase(Locale.ROOT) + "_PASSWORD}");
        }
        boolean query = endpoint.getQuery() != null && !endpoint.getQuery().isBlank();
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", query ? "sql" : "table");
        resource.put("name", query ? endpoint.getQuery() : endpoint.getTable());
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", endpoint.getType());
        section.put("name", name + "-" + endpoint.getType());
        section.put("connection", connection);
        section.put("resource", resource);
        return section;
    }

    private List<String> compareColumns(ServerCompareConfig config, boolean source) {
        com.consilens.server.application.capability.config.ComparisonConfig comparison = config.getComparison();
        if (comparison != null && comparison.getFieldMappings() != null && !comparison.getFieldMappings().isEmpty()) {
            return comparison.getFieldMappings().stream()
                    .map(m -> String.valueOf(source ? m.getSource() : m.getTarget()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return normalized(comparison == null ? null : comparison.getFields());
    }

    private List<String> keyColumns(ServerCompareConfig config, boolean source) {
        com.consilens.server.application.capability.config.ComparisonConfig comparison = config.getComparison();
        if (comparison != null && comparison.getKeyMappings() != null && !comparison.getKeyMappings().isEmpty()) {
            return comparison.getKeyMappings().stream()
                    .map(m -> String.valueOf(source ? m.getSource() : m.getTarget()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return normalized(config.getKeys());
    }

    private List<String> normalized(List<?> values) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            values.forEach(v -> {
                if (v != null && !String.valueOf(v).isBlank()) {
                    result.add(String.valueOf(v));
                }
            });
        }
        return result;
    }

    private void copyIfPresent(Map<String, Object> source, String key, Map<String, Object> target) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> orEmpty(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String contextSubmissionId(RunRequest request) {
        return request.getSerialNo().replaceAll("[^A-Za-z0-9_-]", "-");
    }

    /** 取消检测：RunTaskHandler 传入 taskRepository 轮询逻辑。 */
    public interface CancelChecker {
        boolean isCancellationRequested();
    }
}
