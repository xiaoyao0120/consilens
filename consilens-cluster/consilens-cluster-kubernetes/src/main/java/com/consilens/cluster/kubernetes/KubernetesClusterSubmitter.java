package com.consilens.cluster.kubernetes;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.cluster.kubernetes.gateway.Fabric8KubernetesSubmissionGateway;
import com.consilens.cluster.kubernetes.gateway.KubernetesSubmissionGateway;
import com.consilens.connector.api.planner.ExecutionMode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.KeyToPathBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Kubernetes implementation of the cluster submission SPI, following the
 * spark-on-k8s / flink-on-k8s application model: the runtime ships inside the
 * container image (there is deliberately no jar-upload side channel), the
 * descriptor travels as a ConfigMap the way Flink ships its configuration, and
 * the client blocks until the Job completes unless waitAppCompletion is
 * disabled.
 */
public class KubernetesClusterSubmitter implements ClusterSubmitter, AutoCloseable {

    private static final String COORDINATOR_NAME = "consilens-coordinator";
    private static final String RUNTIME_CLASSPATH = "/opt/consilens/runtime/*";
    private static final String DESCRIPTOR_VOLUME = "comparison-descriptor";
    private static final String DEFAULT_DESCRIPTOR_MOUNT_PATH = "/opt/consilens/descriptor";
    private static final long COMPLETION_POLL_SECONDS = 3;
    private static final Pattern SAFE_SUBMISSION_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final KubernetesSubmissionGateway gateway;

    public KubernetesClusterSubmitter() {
        this(new Fabric8KubernetesSubmissionGateway());
    }

    public KubernetesClusterSubmitter(KubernetesSubmissionGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    @Override
    public ClusterSubmission submit(ClusterSubmitRequest request) {
        KubernetesSubmissionSpec spec = validateAndExtractSpec(request);
        int maxAttempts = maxAttemptsOrDefault(request);
        createDescriptorConfigMapIfNeeded(spec);
        Job created = gateway.create(buildJob(request, spec, maxAttempts));
        return ClusterSubmission.builder()
                .submissionId(request.getSubmissionId())
                .executionMode(ExecutionMode.KUBERNETES)
                .submittedAt(Instant.now())
                .clusterApplicationId(spec.getNamespace() + "/" + created.getMetadata().getName())
                .build();
    }

    @Override
    public ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
        if (submission == null || submission.getClusterApplicationId() == null) {
            return null;
        }
        String[] namespaceAndJob = submission.getClusterApplicationId().split("/", 2);
        if (namespaceAndJob.length != 2) {
            return null;
        }
        long deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos) {
                String status = gateway.jobCompletionStatus(namespaceAndJob[0], namespaceAndJob[1]).orElse(null);
                if ("Complete".equals(status) || "Failed".equals(status)) {
                    return new ClusterApplicationResult(submission.getClusterApplicationId(),
                            "Complete".equals(status) ? "SUCCEEDED" : "FAILED", null, null);
                }
                Thread.sleep(COMPLETION_POLL_SECONDS * 1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Override
    public void close() {
        gateway.close();
    }

    private KubernetesSubmissionSpec validateAndExtractSpec(ClusterSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kubernetes submission request is required");
        }
        request.validate();
        if (request.getExecution().getExecutionMode() != ExecutionMode.KUBERNETES) {
            throw new IllegalArgumentException("Kubernetes submitter only supports KUBERNETES execution mode");
        }
        KubernetesSubmissionSpec spec = request.getKubernetesSubmission();
        if (spec == null) {
            throw new IllegalArgumentException("kubernetesSubmission is required for Kubernetes submission");
        }
        spec.validate();
        if (!SAFE_SUBMISSION_ID.matcher(request.getSubmissionId()).matches()) {
            throw new IllegalArgumentException("submissionId must match [A-Za-z0-9_-]+");
        }
        return spec;
    }

    private Job buildJob(ClusterSubmitRequest request, KubernetesSubmissionSpec spec, int maxAttempts) {
        Map<String, String> labels = new LinkedHashMap<>(spec.sanitizedLabels());
        labels.put("app.kubernetes.io/name", "consilens");
        labels.put("app.kubernetes.io/instance", spec.getJobName());

        Container container = new ContainerBuilder()
                .withName(COORDINATOR_NAME)
                .withImage(spec.getImage())
                .withImagePullPolicy(spec.getImagePullPolicy())
                .withCommand("java")
                .withArgs(commandArguments(request, spec))
                .withEnv(containerEnvironment(spec))
                .withVolumeMounts(descriptorVolumeMounts(spec))
                .withResources(new ResourceRequirementsBuilder()
                        .addToRequests("memory", new Quantity(spec.getMemoryMiB() + "Mi"))
                        .addToRequests("cpu", new Quantity(spec.getCpuMilli() + "m"))
                        .addToLimits("memory", new Quantity(spec.getMemoryMiB() + "Mi"))
                        .addToLimits("cpu", new Quantity(spec.getCpuMilli() + "m"))
                        .build())
                .build();

        PodSpecBuilder podSpec = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withVolumes(descriptorVolumes(spec))
                .withContainers(container);
        if (spec.getServiceAccountName() != null) {
            podSpec.withServiceAccountName(spec.getServiceAccountName());
        }
        if (!spec.sanitizedImagePullSecrets().isEmpty()) {
            podSpec.withImagePullSecrets(spec.sanitizedImagePullSecrets().stream()
                    .map(secretName -> new LocalObjectReference(secretName))
                    .collect(Collectors.toList()));
        }

        return new JobBuilder()
                .withNewMetadata()
                .withName(spec.getJobName())
                .withNamespace(spec.getNamespace())
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(maxAttempts - 1)
                .withTemplate(new PodTemplateSpecBuilder()
                        .withNewMetadata()
                        .withLabels(labels)
                        .endMetadata()
                        .withSpec(podSpec.build())
                        .build())
                .endSpec()
                .build();
    }

    private List<String> commandArguments(ClusterSubmitRequest request, KubernetesSubmissionSpec spec) {
        return List.of(
                "-Xmx" + spec.getMemoryMiB() + "m",
                "-cp",
                RUNTIME_CLASSPATH,
                spec.getCoordinatorMainClass(),
                request.getSubmissionId(),
                descriptorPath(spec));
    }

    private String descriptorPath(KubernetesSubmissionSpec spec) {
        if (spec.getDescriptorData() == null || spec.getDescriptorData().isEmpty()) {
            return spec.getDescriptorUri();
        }
        String fileName = spec.getDescriptorData().keySet().iterator().next();
        return mountPath(spec) + "/" + fileName;
    }

    private String mountPath(KubernetesSubmissionSpec spec) {
        return spec.getDescriptorMountPath() == null || spec.getDescriptorMountPath().trim().isEmpty()
                ? DEFAULT_DESCRIPTOR_MOUNT_PATH
                : spec.getDescriptorMountPath();
    }

    private List<VolumeMount> descriptorVolumeMounts(KubernetesSubmissionSpec spec) {
        if (spec.getDescriptorData() == null || spec.getDescriptorData().isEmpty()) {
            return List.of();
        }
        return List.of(new VolumeMountBuilder()
                .withName(DESCRIPTOR_VOLUME)
                .withMountPath(mountPath(spec))
                .withReadOnly(true)
                .build());
    }

    private List<Volume> descriptorVolumes(KubernetesSubmissionSpec spec) {
        if (spec.getDescriptorData() == null || spec.getDescriptorData().isEmpty()) {
            return List.of();
        }
        List<KeyToPathBuilder> items = spec.getDescriptorData().keySet().stream()
                .map(name -> new KeyToPathBuilder().withKey(name).withPath(name))
                .collect(Collectors.toList());
        return List.of(new VolumeBuilder()
                .withName(DESCRIPTOR_VOLUME)
                .withNewConfigMap()
                .withName(spec.getDescriptorConfigMapName())
                .withItems(items.stream().map(builder -> builder.build()).collect(Collectors.toList()))
                .endConfigMap()
                .build());
    }

    private void createDescriptorConfigMapIfNeeded(KubernetesSubmissionSpec spec) {
        if (spec.getDescriptorData() == null || spec.getDescriptorData().isEmpty()) {
            return;
        }
        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(spec.getDescriptorConfigMapName())
                .withNamespace(spec.getNamespace())
                .endMetadata()
                .withData(spec.sanitizedDescriptorData())
                .build();
        gateway.createConfigMap(configMap);
    }

    private List<EnvVar> containerEnvironment(KubernetesSubmissionSpec spec) {
        List<EnvVar> envVars = new ArrayList<>();
        spec.sanitizedEnvs().forEach((name, value) -> envVars.add(
                new EnvVarBuilder().withName(name).withValue(value).build()));
        spec.sanitizedSecretEnv().forEach((name, secretRef) -> envVars.add(
                secretEnvironment(name, secretRef)));
        return envVars;
    }

    private EnvVar secretEnvironment(String environmentName, KubernetesSecretKeyRef secretRef) {
        return new EnvVarBuilder()
                .withName(environmentName)
                .withNewValueFrom()
                .withNewSecretKeyRef(secretRef.getSecretKey(), secretRef.getSecretName(), false)
                .endValueFrom()
                .build();
    }

    private int maxAttemptsOrDefault(ClusterSubmitRequest request) {
        Integer maxAttempts = request.getExecution().getMaxAttempts();
        if (maxAttempts == null) {
            return 1;
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive for Kubernetes submission");
        }
        return maxAttempts;
    }
}
