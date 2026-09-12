package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Submits one comparison as a Kubernetes Job through the public cluster SPI.
 * The descriptor is parsed only in the Job, so submit hosts do not need
 * connector credentials.
 */
@Command(
        name = "kubernetes",
        aliases = "k8s",
        description = "Submit a comparison to Kubernetes",
        mixinStandardHelpOptions = true
)
public class SubmitKubernetesCommand implements Callable<Integer> {

    private final Supplier<ClusterSubmitter> submitterFactory;
    private final Supplier<String> submissionIdFactory;

    @Option(names = {"-c", "--config"},
            description = "Optional local descriptor reference retained for compatibility; cluster runtime reads --descriptor-uri")
    private String configFile;

    @Option(names = "--image", required = true,
            description = "Container image containing the Consilens coordinator runtime")
    private String image;

    @Option(names = "--descriptor-uri",
            description = "HTTP(S) URI of the redacted submission descriptor for the coordinator to resolve")
    private String descriptorUri;

    @Option(names = "--descriptor",
            description = "Local YAML/JSON descriptor uploaded as a ConfigMap for the coordinator to read")
    private String descriptorFile;

    @Option(names = "--descriptor-config-map",
            description = "ConfigMap name for a local descriptor (default: <job>-descriptor)")
    private String descriptorConfigMapName;

    @Option(names = "--local-runtime",
            description = "Local runtime fat jar uploaded before the Kubernetes Job is created")
    private String localRuntimePath;

    @Option(names = "--runtime-upload-url",
            description = "HTTP(S) root URL used by the submitter to upload --local-runtime")
    private String runtimeUploadUrl;

    @Option(names = "--runtime-download-url",
            description = "HTTP(S) root URL used by the init container to download the runtime artifact")
    private String runtimeDownloadUrl;

    @Option(names = "--init-image",
            description = "Init container image that downloads --local-runtime into the Job pod")
    private String initContainerImage;

    @Option(names = "--coordinator-class", defaultValue = "com.consilens.cluster.application.ClusterComparisonCoordinator",
            description = "Coordinator main class supplied by the runtime image")
    private String coordinatorMainClass;

    @Option(names = "--namespace", defaultValue = "default", description = "Kubernetes namespace (default: default)")
    private String namespace;

    @Option(names = "--name", description = "Job name (default: consilens-<submission id>)")
    private String jobName;

    @Option(names = "--memory", defaultValue = "1024", description = "Coordinator memory in MiB (default: 1024)")
    private int memoryMiB;

    @Option(names = "--cpu-millis", defaultValue = "500", description = "Coordinator CPU in millicores (default: 500)")
    private int cpuMilli;

    @Option(names = "--service-account", description = "Pod service account name")
    private String serviceAccountName;

    @Option(names = "--image-pull-policy", defaultValue = "IfNotPresent",
            description = "Image pull policy: Always, IfNotPresent, or Never")
    private String imagePullPolicy;

    @Option(names = "--label", description = "Job label in key=value form; repeatable")
    private Map<String, String> labels = new LinkedHashMap<>();

    @Option(names = "--secret-env", description = "Environment mapping in ENVIRONMENT=secret-name/secret-key form; repeatable")
    private Map<String, String> secretEnvironmentReferences = new LinkedHashMap<>();

    @Option(names = "--max-attempts", defaultValue = "1",
            description = "Maximum total Job attempts (default: 1)")
    private int maximumAttempts;

    @Spec
    private CommandSpec commandSpec;

    public SubmitKubernetesCommand() {
        this(SubmitKubernetesCommand::createDefaultSubmitter, () -> UUID.randomUUID().toString());
    }

    SubmitKubernetesCommand(Supplier<ClusterSubmitter> submitterFactory,
                            Supplier<String> submissionIdFactory) {
        this.submitterFactory = submitterFactory;
        this.submissionIdFactory = submissionIdFactory;
    }

    SubmitKubernetesCommand(ConfigurationManager configurationManager,
                            CompareRequestFactory compareRequestFactory,
                            Supplier<ClusterSubmitter> submitterFactory,
                            Supplier<String> submissionIdFactory) {
        this(submitterFactory, submissionIdFactory);
    }

    @Override
    public Integer call() {
        ClusterSubmitter submitter = null;
        try {
            String submissionId = submissionIdFactory.get();
            ClusterSubmitRequest submissionRequest = ClusterSubmitRequest.builder()
                    .submissionId(submissionId)
                    .comparison(ClusterComparisonDescription.builder()
                            .sourceConfigRef(descriptorReference(submissionId))
                            .targetConfigRef(descriptorReference(submissionId))
                            .build())
                    .execution(ClusterExecutionDescription.builder()
                            .executionMode(ExecutionMode.KUBERNETES)
                            .maxAttempts(maximumAttempts)
                            .kubernetesNamespace(namespace)
                            .kubernetesImage(image)
                            .build())
                    .build();
            submissionRequest.setKubernetesSubmission(KubernetesSubmissionSpec.builder()
                    .namespace(namespace)
                    .jobName(jobNameOrDefault(submissionId))
                    .image(image)
                    .descriptorUri(descriptorUri)
                    .descriptorData(descriptorData())
                    .descriptorConfigMapName(descriptorConfigMapNameOrDefault(jobNameOrDefault(submissionId)))
                    .descriptorMountPath("/opt/consilens/descriptor")
                    .localRuntimePath(localRuntimePath)
                    .runtimeUploadUrl(runtimeUploadUrl)
                    .runtimeDownloadUrl(runtimeDownloadUrl)
                    .initContainerImage(initContainerImage)
                    .coordinatorMainClass(coordinatorMainClass)
                    .memoryMiB(memoryMiB)
                    .cpuMilli(cpuMilli)
                    .serviceAccountName(serviceAccountName)
                    .imagePullPolicy(imagePullPolicy)
                    .labels(labels)
                    .secretEnv(secretEnvironment())
                    .build());
            validateSubmissionRequest(submissionRequest);
            submitter = submitterFactory.get();
            ClusterSubmission submission = submitter.submit(submissionRequest);
            commandSpec.commandLine().getOut().println(
                    "Kubernetes Job submitted: " + submission.getClusterApplicationId());
            return 0;
        } catch (Exception e) {
            commandSpec.commandLine().getErr().println("Kubernetes submission failed.");
            return 1;
        } finally {
            closeQuietly(submitter);
        }
    }

    private void validateSubmissionRequest(ClusterSubmitRequest submissionRequest) {
        validateDescriptorArgument();
        validateRuntimeArgument();
        submissionRequest.validate();
        submissionRequest.getKubernetesSubmission().validate();
        if (maximumAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive for Kubernetes submission");
        }
    }

    private void validateDescriptorArgument() {
        boolean hasLocal = descriptorFile != null && !descriptorFile.trim().isEmpty();
        boolean hasRemote = descriptorUri != null && !descriptorUri.trim().isEmpty();
        if (hasLocal && hasRemote) {
            throw new IllegalArgumentException("--descriptor and --descriptor-uri are mutually exclusive");
        }
        if (!hasLocal && !hasRemote) {
            throw new IllegalArgumentException("--descriptor or --descriptor-uri is required");
        }
    }

    private void validateRuntimeArgument() {
        boolean hasLocalRuntime = localRuntimePath != null && !localRuntimePath.trim().isEmpty();
        if (!hasLocalRuntime) {
            return;
        }
        if (runtimeUploadUrl == null || runtimeUploadUrl.trim().isEmpty()
                || runtimeDownloadUrl == null || runtimeDownloadUrl.trim().isEmpty()
                || initContainerImage == null || initContainerImage.trim().isEmpty()) {
            throw new IllegalArgumentException("--runtime-upload-url, --runtime-download-url, and --init-image are required with --local-runtime");
        }
        Path path = Paths.get(localRuntimePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("local runtime is not a regular file: " + path);
        }
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("local runtime must be a fat jar for Kubernetes");
        }
    }

    private String jobNameOrDefault(String submissionId) {
        return jobName == null || jobName.trim().isEmpty() ? "consilens-" + submissionId : jobName;
    }

    private String descriptorReference(String submissionId) {
        if (descriptorFile != null && !descriptorFile.trim().isEmpty()) {
            String fileName = Paths.get(descriptorFile).getFileName().toString();
            return "configmap:" + descriptorConfigMapNameOrDefault(jobNameOrDefault(submissionId)) + ":" + fileName;
        }
        return "descriptor:" + descriptorUri;
    }

    private Map<String, String> descriptorData() {
        if (descriptorFile == null || descriptorFile.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        Path path = Paths.get(descriptorFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("local descriptor is not a regular file: " + path);
        }
        String fileName = path.getFileName().toString().toLowerCase();
        if (!(fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".json"))) {
            throw new IllegalArgumentException("local descriptor must be a YAML or JSON file");
        }
        try {
            Map<String, String> data = new LinkedHashMap<>();
            data.put(path.getFileName().toString(), Files.readString(path));
            return data;
        } catch (Exception e) {
            throw new IllegalArgumentException("unable to read local descriptor: " + path, e);
        }
    }

    private String descriptorConfigMapNameOrDefault(String jobName) {
        if (descriptorConfigMapName != null && !descriptorConfigMapName.trim().isEmpty()) {
            return descriptorConfigMapName;
        }
        return jobName + "-descriptor";
    }

    private Map<String, KubernetesSecretKeyRef> secretEnvironment() {
        Map<String, KubernetesSecretKeyRef> result = new LinkedHashMap<>();
        secretEnvironmentReferences.forEach((environmentName, reference) -> {
            int separator = reference == null ? -1 : reference.indexOf('/');
            if (separator <= 0 || separator != reference.lastIndexOf('/') || separator == reference.length() - 1) {
                throw new IllegalArgumentException("secret environment reference must be secret-name/secret-key");
            }
            result.put(environmentName, KubernetesSecretKeyRef.builder()
                    .secretName(reference.substring(0, separator))
                    .secretKey(reference.substring(separator + 1))
                    .build());
        });
        return result;
    }

    private static ClusterSubmitter createDefaultSubmitter() {
        try {
            Class<?> type = Class.forName("com.consilens.cluster.kubernetes.KubernetesClusterSubmitter");
            return (ClusterSubmitter) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Kubernetes client runtime is unavailable; add Fabric8 client libraries", e);
        }
    }

    private void closeQuietly(ClusterSubmitter submitter) {
        if (submitter instanceof AutoCloseable) {
            try {
                ((AutoCloseable) submitter).close();
            } catch (Exception ignored) {
                // Submission outcome is already reported; cleanup failure is not fatal.
            }
        }
    }
}
