package com.consilens.server.application.capability;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.domain.model.TaskExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
 * 经 ClusterSubmitter SPI 提交到 YARN/Kubernetes 并等待终态。
 * YARN descriptor 随 staging 上传；Kubernetes descriptor 通过 ConfigMap 挂载，
 * 密码以 Kubernetes Secret 环境变量占位，避免进入 ConfigMap。
 */
@Component
public class ClusterSubmitSupport {

    private static final long COMPLETION_POLL_SECONDS = 3;

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
        String descriptor = yamlMapper.writeValueAsString(buildDescriptor(config, "kubernetes".equals(platform)));
        Path descriptorFile = null;
        ClusterSubmitter submitter = null;
        ClusterSubmission submission = null;
        boolean reachedTerminalState = false;
        try {
            if ("yarn".equals(platform)) {
                descriptorFile = writeDescriptor(descriptor);
            }
            submitter = submitterLocator.locate(platform);
            submission = submitter.submit(buildSubmitRequest(request, descriptorFile, descriptor, platform));
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
        String platform = request.getOptions().getPlatform().trim().toLowerCase(Locale.ROOT);
        if (!"yarn".equals(platform) && !"kubernetes".equals(platform)) {
            throw new IllegalArgumentException("unsupported cluster platform: " + platform);
        }
        return platform;
    }

    private com.consilens.cluster.api.ClusterSubmitRequest buildSubmitRequest(RunRequest request,
                                                                              Path descriptorFile,
                                                                              String descriptor,
                                                                              String platform) {
        Map<String, Object> params = orEmpty(request.getOptions().getProperties());
        String descriptorReference = "yarn".equals(platform)
                ? descriptorFile.toAbsolutePath().toString()
                : "configmap:" + descriptorConfigMapName(orDefault(params, "jobName", defaultKubernetesJobName(request)));
        com.consilens.cluster.api.ClusterSubmitRequest.ClusterSubmitRequestBuilder builder =
                com.consilens.cluster.api.ClusterSubmitRequest.builder()
                        .submissionId(contextSubmissionId(request))
                        .comparison(com.consilens.cluster.api.ClusterComparisonDescription.builder()
                                .sourceConfigRef("descriptor:" + descriptorReference)
                                .targetConfigRef("descriptor:" + descriptorReference)
                                .build())
                        .execution(com.consilens.cluster.api.ClusterExecutionDescription.builder()
                                .executionMode("yarn".equals(platform)
                                        ? com.consilens.connector.api.planner.ExecutionMode.YARN
                                        : com.consilens.connector.api.planner.ExecutionMode.KUBERNETES)
                                .maxAttempts(positiveInteger(params.get("maxAppAttempts"), "maxAppAttempts"))
                                .build());
        if ("yarn".equals(platform)) {
            String descriptorUri = descriptorFile.toAbsolutePath().toString();
            builder.yarnSubmission(YarnSubmissionSpec.builder()
                    .runtimeArchiveUri(required(params, "archive", "yarn.archive"))
                    .descriptorUri(descriptorUri)
                    .stagingUri((String) params.get("stagingDir"))
                    .applicationName(contextSubmissionId(request))
                    .queue((String) params.get("queue"))
                    .amMemoryMb(parseMemory(orDefault(params, "amMemory", "1g")))
                    .amVCores(intOrDefault(params, "amVCores", 1))
                    .tags(splitCsv((String) params.get("tags")))
                    .files(splitCsv((String) params.get("files")))
                    .jars(splitCsv((String) params.get("jars")))
                    .build());
        } else {
            String jobName = orDefault(params, "jobName", defaultKubernetesJobName(request));
            Map<String, KubernetesSecretKeyRef> secretEnv = secretEnvsOf(params.get("secretEnv"));
            requirePasswordSecret(descriptor, secretEnv, "SOURCE_PASSWORD");
            requirePasswordSecret(descriptor, secretEnv, "TARGET_PASSWORD");
            builder.kubernetesSubmission(KubernetesSubmissionSpec.builder()
                    .namespace(orDefault(params, "namespace", "default"))
                    .jobName(jobName)
                    .image(required(params, "image", "kubernetes.image"))
                    .descriptorData(Map.of("comparison.yaml", descriptor))
                    .descriptorConfigMapName(descriptorConfigMapName(jobName))
                    .descriptorMountPath("/opt/consilens/descriptor")
                    .memoryMiB(parseMemory(orDefault(params, "memory", "1g")))
                    .cpuMilli((int) Math.round(Double.parseDouble(
                            orDefault(params, "cpu", "0.5").trim()) * 1000))
                    .serviceAccountName(stringParam(params, "serviceAccount"))
                    .imagePullSecrets(splitCsv((String) params.get("imagePullSecrets")))
                    .envs(envsOf(params.get("envs")))
                    .secretEnv(secretEnv)
                    .build());
        }
        com.consilens.cluster.api.ClusterSubmitRequest submitRequest = builder.build();
        submitRequest.validate();
        return submitRequest;
    }

    private Path writeDescriptor(String descriptor) throws Exception {
        Path descriptorFile = Files.createTempFile("consilens-descriptor-", ".yaml");
        Files.writeString(descriptorFile, descriptor);
        return descriptorFile;
    }

    /** ServerCompareConfig → CLI 兼容 descriptor（与 examples/*.yaml 同构）。 */
    Map<String, Object> buildDescriptor(ServerCompareConfig config, boolean kubernetes) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("source", endpointDescriptor(config.getSource(), "source", kubernetes));
        descriptor.put("target", endpointDescriptor(config.getTarget(), "target", kubernetes));

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
        strategy.put("mode", "join");
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
                                                   boolean kubernetes) {
        if (endpoint == null || endpoint.getConnection() == null || endpoint.getConnection().isEmpty()) {
            throw new IllegalArgumentException("endpoint connection is required for cluster submission: " + name);
        }
        Map<String, Object> connection = new LinkedHashMap<>(endpoint.getConnection());
        if (kubernetes && connection.get("password") != null) {
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

    private Map<String, String> envsOf(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return result;
    }

    private Map<String, KubernetesSecretKeyRef> secretEnvsOf(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, KubernetesSecretKeyRef> result = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((environmentName, reference) -> {
            if (!(reference instanceof Map)) {
                throw new IllegalArgumentException("secretEnv." + environmentName + " must be an object");
            }
            Map<?, ?> values = (Map<?, ?>) reference;
            result.put(String.valueOf(environmentName), KubernetesSecretKeyRef.builder()
                    .secretName(stringValue(values.get("secretName")))
                    .secretKey(stringValue(values.get("secretKey")))
                    .build());
        });
        return result;
    }

    private String orDefault(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String required(Map<String, Object> params, String key, String name) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("cluster parameter is required: " + name);
        }
        return String.valueOf(value);
    }

    private int parseMemory(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d+)\\s*([bkmgt]?)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid memory format: " + value + " (expected e.g. 1g, 2048m)");
        }
        long amount = Long.parseLong(matcher.group(1));
        switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "":  return Math.toIntExact(amount);
            case "b": return Math.toIntExact(Math.max(1, amount / (1024 * 1024)));
            case "k": return Math.toIntExact(Math.max(1, amount / 1024));
            case "m": return Math.toIntExact(amount);
            case "g": return Math.toIntExact(amount * 1024);
            case "t": return Math.toIntExact(amount * 1024 * 1024);
            default:  throw new IllegalArgumentException("invalid memory format: " + value);
        }
    }

    private int intOrDefault(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    private Integer positiveInteger(Object value, String name) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        int parsed = Integer.parseInt(String.valueOf(value));
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private String defaultKubernetesJobName(RunRequest request) {
        String suffix = contextSubmissionId(request).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("^-+|-+$", "");
        if (suffix.isEmpty()) {
            suffix = "run";
        }
        return ("consilens-" + suffix).substring(0, Math.min(52, "consilens-".length() + suffix.length()))
                .replaceAll("-+$", "");
    }

    private String descriptorConfigMapName(String jobName) {
        return (jobName + "-descriptor").substring(0, Math.min(63, jobName.length() + "-descriptor".length()))
                .replaceAll("-+$", "");
    }

    private void requirePasswordSecret(String descriptor,
                                       Map<String, KubernetesSecretKeyRef> secretEnv,
                                       String environmentName) {
        if (descriptor.contains("${env." + environmentName + "}") && !secretEnv.containsKey(environmentName)) {
            throw new IllegalArgumentException("secretEnv." + environmentName
                    + " is required when the Kubernetes descriptor contains a database password");
        }
    }

    private String stringParam(Map<String, Object> params, String key) {
        return params.get(key) == null ? null : String.valueOf(params.get(key));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (String item : value.split(",")) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return result;
    }

    private String contextSubmissionId(RunRequest request) {
        return request.getSerialNo().replaceAll("[^A-Za-z0-9_-]", "-");
    }

    /** 取消检测：RunTaskHandler 传入 taskRepository 轮询逻辑。 */
    public interface CancelChecker {
        boolean isCancellationRequested();
    }
}
