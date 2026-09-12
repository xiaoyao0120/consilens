package com.consilens.cluster.kubernetes;

import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.cluster.kubernetes.gateway.Fabric8KubernetesSubmissionGateway;
import com.consilens.cluster.kubernetes.gateway.HttpRuntimeArtifactUploader;
import com.consilens.cluster.kubernetes.gateway.KubernetesSubmissionGateway;
import com.consilens.cluster.kubernetes.gateway.RuntimeArtifactUploader;
import com.consilens.connector.api.planner.ExecutionMode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Kubernetes implementation of the cluster submission SPI. It creates one
 * application-style Job whose runtime image resolves the external descriptor URI.
 */
public class KubernetesClusterSubmitter implements ClusterSubmitter, AutoCloseable {

    private static final String COORDINATOR_NAME = "consilens-coordinator";
    private static final String RUNTIME_CLASSPATH = "/opt/consilens/runtime/*";
    private static final String DESCRIPTOR_VOLUME = "comparison-descriptor";
    private static final String DEFAULT_DESCRIPTOR_MOUNT_PATH = "/opt/consilens/descriptor";
    private static final String RUNTIME_VOLUME = "consilens-runtime";
    private static final Pattern SAFE_SUBMISSION_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final KubernetesSubmissionGateway gateway;
    private final RuntimeArtifactUploader runtimeArtifactUploader;

    public KubernetesClusterSubmitter() {
        this(new Fabric8KubernetesSubmissionGateway(), new HttpRuntimeArtifactUploader());
    }

    public KubernetesClusterSubmitter(KubernetesSubmissionGateway gateway) {
        this(gateway, new HttpRuntimeArtifactUploader());
    }

    public KubernetesClusterSubmitter(KubernetesSubmissionGateway gateway,
                                      RuntimeArtifactUploader runtimeArtifactUploader) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.runtimeArtifactUploader = Objects.requireNonNull(runtimeArtifactUploader, "runtimeArtifactUploader");
    }

    @Override
    public ClusterSubmission submit(ClusterSubmitRequest request) {
        KubernetesSubmissionSpec spec = validateAndExtractSpec(request);
        int maxAttempts = maxAttemptsOrDefault(request);
        createDescriptorConfigMapIfNeeded(spec);
        KubernetesSubmissionSpec resolvedSpec = RuntimeResolver.resolve(spec, runtimeArtifactUploader);
        Job created = gateway.create(buildJob(request, resolvedSpec, maxAttempts));
        return ClusterSubmission.builder()
                .submissionId(request.getSubmissionId())
                .executionMode(ExecutionMode.KUBERNETES)
                .submittedAt(Instant.now())
                .clusterApplicationId(spec.getNamespace() + "/" + created.getMetadata().getName())
                .build();
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
                .withEnv(secretEnvironment(spec))
                .withVolumeMounts(combinedVolumeMounts(spec))
                .withResources(new ResourceRequirementsBuilder()
                        .addToRequests("memory", new Quantity(spec.getMemoryMiB() + "Mi"))
                        .addToRequests("cpu", new Quantity(spec.getCpuMilli() + "m"))
                        .addToLimits("memory", new Quantity(spec.getMemoryMiB() + "Mi"))
                        .addToLimits("cpu", new Quantity(spec.getCpuMilli() + "m"))
                        .build())
                .build();

        PodSpecBuilder podSpec = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withVolumes(combinedVolumes(spec))
                .withInitContainers(initContainers(spec))
                .withContainers(container);
        if (spec.getServiceAccountName() != null) {
            podSpec.withServiceAccountName(spec.getServiceAccountName());
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

    private List<VolumeMount> combinedVolumeMounts(KubernetesSubmissionSpec spec) {
        List<VolumeMount> mounts = new ArrayList<>();
        mounts.addAll(descriptorVolumeMounts(spec));
        mounts.addAll(runtimeVolumeMounts(spec));
        return mounts;
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

    private List<Volume> combinedVolumes(KubernetesSubmissionSpec spec) {
        List<Volume> volumes = new ArrayList<>();
        volumes.addAll(descriptorVolumes(spec));
        volumes.addAll(runtimeVolumes(spec));
        return volumes;
    }

    private List<VolumeMount> runtimeVolumeMounts(KubernetesSubmissionSpec spec) {
        if (spec.getLocalRuntimePath() == null || spec.getLocalRuntimePath().trim().isEmpty()) {
            return List.of();
        }
        return List.of(new VolumeMountBuilder()
                .withName(RUNTIME_VOLUME)
                .withMountPath("/opt/consilens/runtime")
                .build());
    }

    private List<Volume> runtimeVolumes(KubernetesSubmissionSpec spec) {
        if (spec.getLocalRuntimePath() == null || spec.getLocalRuntimePath().trim().isEmpty()) {
            return List.of();
        }
        return List.of(new VolumeBuilder()
                .withName(RUNTIME_VOLUME)
                .withNewEmptyDir()
                .endEmptyDir()
                .build());
    }

    private List<Container> initContainers(KubernetesSubmissionSpec spec) {
        if (spec.getLocalRuntimePath() == null || spec.getLocalRuntimePath().trim().isEmpty()) {
            return List.of();
        }
        String fileName = Paths.get(spec.getLocalRuntimePath()).getFileName().toString();
        String downloadUrl = directoryUri(spec.getRuntimeDownloadUrl()).resolve(fileName).toString();
        Container initContainer = new ContainerBuilder()
                .withName("runtime-download")
                .withImage(spec.getInitContainerImage())
                .withCommand("curl")
                .withArgs("-f", "-L", "-o", "/opt/consilens/runtime/" + fileName, downloadUrl)
                .withVolumeMounts(new VolumeMountBuilder()
                        .withName(RUNTIME_VOLUME)
                        .withMountPath("/opt/consilens/runtime")
                        .build())
                .build();
        return List.of(initContainer);
    }

    private URI directoryUri(String value) {
        String normalized = value.endsWith("/") ? value : value + "/";
        return URI.create(normalized);
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

    private List<EnvVar> secretEnvironment(KubernetesSubmissionSpec spec) {
        return spec.sanitizedSecretEnv().entrySet().stream()
                .map(entry -> secretEnvironment(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
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

    private static final class RuntimeResolver {

        private RuntimeResolver() {
        }

        private static KubernetesSubmissionSpec resolve(KubernetesSubmissionSpec spec,
                                                        RuntimeArtifactUploader uploader) {
            if (spec.getLocalRuntimePath() == null || spec.getLocalRuntimePath().trim().isEmpty()) {
                return spec;
            }
            Path localPath = Paths.get(spec.getLocalRuntimePath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(localPath)) {
                throw new IllegalArgumentException("local runtime is not a regular file: " + localPath);
            }
            uploader.upload(localPath, URI.create(spec.getRuntimeUploadUrl()));
            KubernetesSubmissionSpec resolved = KubernetesSubmissionSpec.builder()
                    .namespace(spec.getNamespace())
                    .jobName(spec.getJobName())
                    .image(spec.getImage())
                    .descriptorUri(spec.getDescriptorUri())
                    .descriptorData(spec.sanitizedDescriptorData())
                    .descriptorConfigMapName(spec.getDescriptorConfigMapName())
                    .descriptorMountPath(spec.getDescriptorMountPath())
                    .localRuntimePath(localPath.toString())
                    .runtimeUploadUrl(spec.getRuntimeUploadUrl())
                    .runtimeDownloadUrl(spec.getRuntimeDownloadUrl())
                    .initContainerImage(spec.getInitContainerImage())
                    .coordinatorMainClass(spec.getCoordinatorMainClass())
                    .memoryMiB(spec.getMemoryMiB())
                    .cpuMilli(spec.getCpuMilli())
                    .serviceAccountName(spec.getServiceAccountName())
                    .imagePullPolicy(spec.getImagePullPolicy())
                    .labels(spec.sanitizedLabels())
                    .secretEnv(spec.sanitizedSecretEnv())
                    .build();
            resolved.validate();
            return resolved;
        }
    }
}
