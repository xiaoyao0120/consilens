package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmitKubernetesCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildRedactedRequestAndPrintJobReceipt() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        String password = "password-that-must-not-be-published";
        Files.writeString(configurationFile, configuration(password));
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitKubernetesCommand command = command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .submittedAt(Instant.parse("2026-08-25T00:00:00Z"))
                .clusterApplicationId("data-quality/consilens-orders")
                .build());

        int exitCode = commandLine(command, output, error).execute(
                "-c", configurationFile.toString(),
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://quality-configs.example/consilens/orders.json",
                "--coordinator-class", "com.consilens.runtime.KubernetesCoordinator",
                "--namespace", "data-quality", "--name", "consilens-orders",
                "--memory", "2048", "--cpu-millis", "750", "--max-attempts", "3",
                "--service-account", "consilens-runner", "--label", "team=quality",
                "--label", "app.kubernetes.io/part-of=quality-platform",
                "--secret-env", "SOURCE_PASSWORD=consilens-database/source-password");

        ClusterSubmitRequest request = captured.get();
        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("data-quality/consilens-orders"));
        assertEquals(ExecutionMode.KUBERNETES, request.getExecution().getExecutionMode());
        assertEquals("registry.example/consilens:1.0.0", request.getKubernetesSubmission().getImage());
        assertEquals("data-quality", request.getKubernetesSubmission().getNamespace());
        assertEquals("consilens-orders", request.getKubernetesSubmission().getJobName());
        assertEquals(2048, request.getKubernetesSubmission().getMemoryMiB());
        assertEquals(750, request.getKubernetesSubmission().getCpuMilli());
        assertEquals("quality", request.getKubernetesSubmission().getLabels().get("team"));
        assertEquals("quality-platform", request.getKubernetesSubmission().getLabels()
                .get("app.kubernetes.io/part-of"));
        assertEquals("consilens-database", request.getKubernetesSubmission().getSecretEnv()
                .get("SOURCE_PASSWORD").getSecretName());
        assertEquals("source-password", request.getKubernetesSubmission().getSecretEnv()
                .get("SOURCE_PASSWORD").getSecretKey());
        assertFalse(serialized(request).contains(password));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(password));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    @Test
    void shouldUseClusterComparisonCoordinatorByDefault() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(configurationFile, configuration("${env.CLUSTER_ONLY_PASSWORD}"));
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-submission-kubernetes-1")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "-c", configurationFile.toString(),
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://configs.example/consilens/submission.yaml");

        assertEquals(0, exitCode);
        assertEquals("com.consilens.cluster.application.ClusterComparisonCoordinator",
                captured.get().getKubernetesSubmission().getCoordinatorMainClass());
    }

    @Test
    void shouldRejectMissingRequiredKubernetesOptionsBeforeSubmitterCall() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(configurationFile, configuration("password"));
        AtomicBoolean called = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    called.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-2");
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error)
                .execute("-c", configurationFile.toString(), "--image", "registry.example/consilens:1.0.0");

        assertEquals(1, exitCode);
        assertFalse(called.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("Kubernetes submission failed."));
    }

    @Test
    void shouldPreflightInvalidKubernetesParametersBeforeCreatingSubmitter() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(configurationFile, configuration("password"));
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-3");

        int invalidUriExitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "-c", configurationFile.toString(),
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "s3a://quality-configs/orders.json",
                "--coordinator-class", "com.consilens.runtime.KubernetesCoordinator");

        assertEquals(1, invalidUriExitCode);
        assertFalse(factoryCalled.get());

        SubmitKubernetesCommand attemptsCommand = new SubmitKubernetesCommand(new ConfigurationManager(),
                new CompareRequestFactory(), () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-4");
        int invalidAttemptsExitCode = commandLine(attemptsCommand, new ByteArrayOutputStream(),
                new ByteArrayOutputStream()).execute(
                "-c", configurationFile.toString(),
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://quality-configs.example/orders.json",
                "--coordinator-class", "com.consilens.runtime.KubernetesCoordinator",
                "--max-attempts", "0");

        assertEquals(1, invalidAttemptsExitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldHideSecretWhenSubmitterFactoryFails() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        String password = "password-that-must-not-be-logged";
        Files.writeString(configurationFile, configuration(password));
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    throw new IllegalStateException(password);
                }, () -> "submission-kubernetes-5");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error).execute(
                "-c", configurationFile.toString(),
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://quality-configs.example/orders.json",
                "--coordinator-class", "com.consilens.runtime.KubernetesCoordinator");

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("Kubernetes submission failed."));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    @Test
    void shouldUploadLocalDescriptorAsConfigMap() throws Exception {
        String localDescriptor = "source:\n  type: mysql\n  connection:\n    password: ${env.SOURCE_PASSWORD}\n"
                + "target:\n  type: mysql\n  connection:\n    password: ${env.TARGET_PASSWORD}\n";
        Path descriptorFile = temporaryDirectory.resolve("local-comparison.yaml");
        Files.writeString(descriptorFile, localDescriptor);
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-local")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString(),
                "--namespace", "default",
                "--name", "consilens-local");

        assertEquals(0, exitCode);
        KubernetesSubmissionSpec spec = captured.get().getKubernetesSubmission();
        assertEquals("local-comparison.yaml", spec.getDescriptorData().keySet().iterator().next());
        assertEquals("consilens-local-descriptor", spec.getDescriptorConfigMapName());
        assertEquals("/opt/consilens/descriptor", spec.getDescriptorMountPath());
        assertTrue(spec.getDescriptorData().get("local-comparison.yaml").contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldAcceptLocalRuntimeWithUploadEndpoint() throws Exception {
        Path runtime = temporaryDirectory.resolve("consilens-runtime.jar");
        Files.write(runtime, new byte[]{1, 2, 3});
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-local")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://configs.example/consilens/comparison.yaml",
                "--local-runtime", runtime.toString(),
                "--runtime-upload-url", "https://artifact.example/consilens",
                "--runtime-download-url", "https://artifact.example/consilens",
                "--init-image", "curlimages/curl:8.4.0");

        assertEquals(0, exitCode);
        KubernetesSubmissionSpec spec = captured.get().getKubernetesSubmission();
        assertEquals(runtime.toString(), spec.getLocalRuntimePath());
        assertEquals("https://artifact.example/consilens", spec.getRuntimeUploadUrl());
        assertEquals("https://artifact.example/consilens", spec.getRuntimeDownloadUrl());
        assertEquals("curlimages/curl:8.4.0", spec.getInitContainerImage());
    }

    @Test
    void shouldRejectPlaintextDescriptorBeforeSubmitterCall() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("plaintext.yaml");
        Files.writeString(descriptorFile, "source:\n  password: real-password\n");
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-6");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString());

        assertEquals(1, exitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldRejectPlaintextJsonDescriptorBeforeSubmitterCall() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("plaintext.json");
        Files.writeString(descriptorFile, "{\n  \"source\": {\n    \"password\": \"real-password\"\n  }\n}");
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-8");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString());

        assertEquals(1, exitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldAcceptJsonDescriptorWithEnvironmentPlaceholder() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("placeholder.json");
        Files.writeString(descriptorFile,
                "{\n  \"source\": {\n    \"password\": \"${env.SOURCE_PASSWORD}\"\n  }\n}");
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-9")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-placeholder")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString(),
                "--namespace", "default",
                "--name", "consilens-placeholder");

        assertEquals(0, exitCode);
        assertTrue(captured.get().getKubernetesSubmission().getDescriptorData()
                .get("placeholder.json").contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldRejectCompactJsonDescriptorBeforeSubmitterCall() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("compact.json");
        Files.writeString(descriptorFile, "{\"source\":{\"password\":\"real-password\"}}");
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-10");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString());

        assertEquals(1, exitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldAcceptCompactJsonDescriptorWithEnvironmentPlaceholder() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("compact-placeholder.json");
        Files.writeString(descriptorFile, "{\"source\":{\"password\":\"${env.SOURCE_PASSWORD}\"}}");
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-11")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-compact")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString(),
                "--namespace", "default",
                "--name", "consilens-compact");

        assertEquals(0, exitCode);
        assertTrue(captured.get().getKubernetesSubmission().getDescriptorData()
                .get("compact-placeholder.json").contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldRejectYamlFlowStylePlaintextDescriptorBeforeSubmitterCall() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("flow.yaml");
        Files.writeString(descriptorFile, "{source: {password: real-password}}");
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-12");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString());

        assertEquals(1, exitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldRejectMalformedDescriptorBeforeSubmitterCall() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("malformed.yaml");
        Files.writeString(descriptorFile, "source: [not: valid: yaml");
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-14");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor", descriptorFile.toString());

        assertEquals(1, exitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldRejectUnsafeRuntimeFileNameBeforeSubmitterCall() throws Exception {
        Path runtime = temporaryDirectory.resolve("consilens-runtime;rm.jar");
        Files.write(runtime, new byte[]{1, 2, 3});
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-7");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://configs.example/consilens/comparison.yaml",
                "--local-runtime", runtime.toString(),
                "--runtime-upload-url", "https://artifact.example/consilens",
                "--runtime-download-url", "https://artifact.example/consilens",
                "--init-image", "curlimages/curl:8.4.0");

        assertEquals(1, exitCode);
        assertFalse(factoryCalled.get());
    }

    private SubmitKubernetesCommand command(AtomicReference<ClusterSubmitRequest> captured,
                                             Supplier<ClusterSubmission> receiptFactory) {
        return new SubmitKubernetesCommand(new ConfigurationManager(), new CompareRequestFactory(),
                () -> new RecordingSubmitter(captured, receiptFactory), () -> "submission-kubernetes-1");
    }

    private CommandLine commandLine(SubmitKubernetesCommand command,
                                    ByteArrayOutputStream output,
                                    ByteArrayOutputStream error) {
        CommandLine commandLine = new CommandLine(command);
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true));
        commandLine.setErr(new PrintWriter(new OutputStreamWriter(error, StandardCharsets.UTF_8), true));
        return commandLine;
    }

    private String serialized(ClusterSubmitRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutput = new ObjectOutputStream(output)) {
            objectOutput.writeObject(request);
        }
        return output.toString(StandardCharsets.ISO_8859_1);
    }

    private String configuration(String password) {
        return "source:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/source_db\n"
                + "    username: source_user\n"
                + "    password: " + password + "\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: source_orders\n"
                + "target:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/target_db\n"
                + "    username: target_user\n"
                + "    password: " + password + "\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: target_orders\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [value]\n"
                + "    target: [value]\n"
                + "strategy:\n"
                + "  mode: checksum\n"
                + "  algorithm: concat\n"
                + "  bisectionFactor: 4\n"
                + "  bisectionThreshold: 1000\n"
                + "  batchSize: 100\n"
                + "  localCompare:\n"
                + "    mode: full\n";
    }

    private static final class RecordingSubmitter implements ClusterSubmitter {

        private final AtomicReference<ClusterSubmitRequest> captured;
        private final Supplier<ClusterSubmission> receiptFactory;

        private RecordingSubmitter(AtomicReference<ClusterSubmitRequest> captured,
                                   Supplier<ClusterSubmission> receiptFactory) {
            this.captured = captured;
            this.receiptFactory = receiptFactory;
        }

        @Override
        public ClusterSubmission submit(ClusterSubmitRequest request) {
            request.validate();
            request.getKubernetesSubmission().validate();
            captured.set(request);
            return receiptFactory.get();
        }
    }
}
