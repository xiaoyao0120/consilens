package com.consilens.cluster.kubernetes;

import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.kubernetes.gateway.KubernetesSubmissionGateway;
import com.consilens.cluster.kubernetes.gateway.RuntimeArtifactUploader;
import com.consilens.connector.api.planner.ExecutionMode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesClusterSubmitterTest {

    @Test
    void shouldMapCompleteJobThroughGateway() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = KubernetesSubmissionSpec.builder()
                .namespace("data-quality")
                .jobName("consilens-orders-compare")
                .image("registry.example/consilens:1.0.0")
                .descriptorUri("https://quality-configs.example/consilens/orders.json")
                .coordinatorMainClass("com.consilens.runtime.KubernetesCoordinator")
                .memoryMiB(2048)
                .cpuMilli(750)
                .serviceAccountName("consilens-runner")
                .imagePullPolicy("IfNotPresent")
                .labels(Map.of("example.com/team", "quality"))
                .secretEnv(Map.of("SOURCE_PASSWORD", KubernetesSecretKeyRef.builder()
                        .secretName("consilens-database")
                        .secretKey("source-password")
                        .build()))
                .build();

        ClusterSubmission submission = new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 3));

        Job job = gateway.createdJob;
        assertEquals("data-quality", job.getMetadata().getNamespace());
        assertEquals("consilens-orders-compare", job.getMetadata().getName());
        assertEquals("quality", job.getMetadata().getLabels().get("example.com/team"));
        assertEquals("consilens", job.getMetadata().getLabels().get("app.kubernetes.io/name"));
        assertEquals(2, job.getSpec().getBackoffLimit());
        assertEquals("Never", job.getSpec().getTemplate().getSpec().getRestartPolicy());
        assertEquals("consilens-runner", job.getSpec().getTemplate().getSpec().getServiceAccountName());

        Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertEquals("consilens-coordinator", container.getName());
        assertEquals("registry.example/consilens:1.0.0", container.getImage());
        assertEquals("IfNotPresent", container.getImagePullPolicy());
        assertEquals(List.of("java"), container.getCommand());
        assertTrue(container.getArgs().contains("-Xmx2048m"));
        assertTrue(container.getArgs().contains("com.consilens.runtime.KubernetesCoordinator"));
        assertTrue(container.getArgs().contains("submission-kubernetes-1"));
        assertTrue(container.getArgs().contains("https://quality-configs.example/consilens/orders.json"));
        assertEquals("consilens-database", container.getEnv().get(0).getValueFrom().getSecretKeyRef().getName());
        assertEquals("source-password", container.getEnv().get(0).getValueFrom().getSecretKeyRef().getKey());
        assertEquals("2048", container.getResources().getRequests().get("memory").getAmount());
        assertEquals("Mi", container.getResources().getRequests().get("memory").getFormat());
        assertEquals("750", container.getResources().getLimits().get("cpu").getAmount());
        assertEquals("m", container.getResources().getLimits().get("cpu").getFormat());
        assertFalse(container.getArgs().toString().contains("password"));

        assertEquals("data-quality/consilens-orders-compare", submission.getClusterApplicationId());
        assertEquals(ExecutionMode.KUBERNETES, submission.getExecutionMode());
        assertEquals(1, gateway.createCalls);
    }

    @Test
    void shouldRejectNonKubernetesModeBeforeContactingGateway() {
        RecordingGateway gateway = new RecordingGateway();
        ClusterSubmitRequest request = kubernetesRequest(validSpec(), 1);
        request.getExecution().setExecutionMode(ExecutionMode.YARN);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway).submit(request));

        assertTrue(error.getMessage().contains("only supports KUBERNETES"));
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void shouldRejectInvalidSpecAndAttemptsBeforeContactingGateway() {
        KubernetesSubmissionSpec invalidUri = validSpec();
        invalidUri.setDescriptorUri("s3a://quality-configs/orders.json");
        assertRejectedBeforeGateway(kubernetesRequest(invalidUri, 1));

        KubernetesSubmissionSpec invalidClass = validSpec();
        invalidClass.setCoordinatorMainClass("java -jar runtime");
        assertRejectedBeforeGateway(kubernetesRequest(invalidClass, 1));

        KubernetesSubmissionSpec invalidMemory = validSpec();
        invalidMemory.setMemoryMiB(0);
        assertRejectedBeforeGateway(kubernetesRequest(invalidMemory, 1));

        KubernetesSubmissionSpec invalidLabel = validSpec();
        invalidLabel.setLabels(Map.of("invalid..prefix/team", "quality"));
        assertRejectedBeforeGateway(kubernetesRequest(invalidLabel, 1));

        assertRejectedBeforeGateway(kubernetesRequest(validSpec(), 0));
        assertRejectedBeforeGateway(kubernetesRequest(validSpec(), -1));
    }

    @Test
    void shouldUseOneBackoffRetryForTwoAttemptsAndNotLeakLabelsIntoCommand() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setLabels(Map.of("secret-label", "secret-value"));

        new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 2));

        assertEquals(1, gateway.createdJob.getSpec().getBackoffLimit());
        assertFalse(gateway.createdJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().toString()
                .contains("secret-value"));
    }

    @Test
    void shouldCreateConfigMapAndMountLocalDescriptor() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.yaml", "source:\n  password: ${env.SOURCE_PASSWORD}\n");
        KubernetesSubmissionSpec spec = validSpec();
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-orders-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 1));

        assertEquals(1, gateway.createConfigMapCalls);
        assertEquals("consilens-orders-descriptor", gateway.createdConfigMap.getMetadata().getName());
        assertEquals("comparison.yaml", gateway.createdConfigMap.getData().keySet().iterator().next());
        String arg = gateway.createdJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().toString();
        assertTrue(gateway.createdJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs()
                .contains("/opt/consilens/descriptor/comparison.yaml"));
        assertFalse(arg.contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldUploadLocalRuntimeAndAddInitContainer() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        RecordingUploader uploader = new RecordingUploader();
        Path runtime = Files.createTempFile("consilens-runtime", ".jar");
        Files.write(runtime, new byte[]{1, 2, 3});
        KubernetesSubmissionSpec spec = validSpec();
        spec.setLocalRuntimePath(runtime.toString());
        spec.setRuntimeUploadUrl("https://artifact.example/consilens");
        spec.setRuntimeDownloadUrl("https://artifact.example/consilens");
        spec.setInitContainerImage("curlimages/curl:8.4.0");

        new KubernetesClusterSubmitter(gateway, uploader).submit(kubernetesRequest(spec, 1));

        assertEquals(1, uploader.uploadCalls);
        assertEquals(1, gateway.createdJob.getSpec().getTemplate().getSpec().getInitContainers().size());
        List<String> initCommand = gateway.createdJob.getSpec().getTemplate().getSpec().getInitContainers().get(0).getCommand();
        List<String> initArgs = gateway.createdJob.getSpec().getTemplate().getSpec().getInitContainers().get(0).getArgs();
        assertEquals(List.of("curl"), initCommand);
        assertTrue(initArgs.contains("https://artifact.example/consilens/" + runtime.getFileName()));
        assertFalse(initCommand.toString().contains("sh"));
        assertTrue(gateway.createdJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs()
                .contains("com.consilens.runtime.KubernetesCoordinator"));
        Files.deleteIfExists(runtime);
    }

    @Test
    void shouldRejectPlaintextDescriptorSecretBeforeCreatingConfigMap() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.yaml", "source:\n  password: real-password\n");
        KubernetesSubmissionSpec spec = validSpec();
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-orders-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 1)));
        assertEquals(0, gateway.createConfigMapCalls);
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void shouldRejectPlaintextJsonDescriptorSecretBeforeCreatingConfigMap() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json", "{\n  \"source\": {\n    \"password\": \"real-password\"\n  }\n}");
        KubernetesSubmissionSpec spec = validSpec();
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-orders-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 1)));
        assertEquals(0, gateway.createConfigMapCalls);
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void shouldRejectCompactJsonDescriptorSecretBeforeCreatingConfigMap() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json", "{\"source\":{\"password\":\"real-password\"}}");
        KubernetesSubmissionSpec spec = validSpec();
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-orders-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 1)));
        assertEquals(0, gateway.createConfigMapCalls);
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void shouldRejectYamlFlowStylePasswordBeforeCreatingConfigMap() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.yaml", "{source: {password: real-password}}");
        KubernetesSubmissionSpec spec = validSpec();
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-orders-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 1)));
        assertEquals(0, gateway.createConfigMapCalls);
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void shouldRejectUnsafeRuntimeFileNameBeforeUploading() {
        RecordingGateway gateway = new RecordingGateway();
        RecordingUploader uploader = new RecordingUploader();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setLocalRuntimePath("consilens-runtime;rm.jar");
        spec.setRuntimeUploadUrl("https://artifact.example/consilens");
        spec.setRuntimeDownloadUrl("https://artifact.example/consilens");
        spec.setInitContainerImage("curlimages/curl:8.4.0");

        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway, uploader).submit(kubernetesRequest(spec, 1)));
        assertEquals(0, uploader.uploadCalls);
        assertEquals(0, gateway.createCalls);
    }

    private void assertRejectedBeforeGateway(ClusterSubmitRequest request) {
        RecordingGateway gateway = new RecordingGateway();
        assertThrows(IllegalArgumentException.class, () -> new KubernetesClusterSubmitter(gateway).submit(request));
        assertEquals(0, gateway.createCalls);
    }

    private KubernetesSubmissionSpec validSpec() {
        return KubernetesSubmissionSpec.builder()
                .namespace("default")
                .jobName("consilens-compare")
                .image("registry.example/consilens:latest")
                .descriptorUri("https://quality-configs.example/consilens/descriptor.json")
                .coordinatorMainClass("com.consilens.runtime.KubernetesCoordinator")
                .memoryMiB(1024)
                .cpuMilli(500)
                .build();
    }

    private ClusterSubmitRequest kubernetesRequest(KubernetesSubmissionSpec spec, int maxAttempts) {
        return ClusterSubmitRequest.builder()
                .submissionId("submission-kubernetes-1")
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("descriptor/source")
                        .targetConfigRef("descriptor/target")
                        .build())
                .execution(ClusterExecutionDescription.builder()
                        .executionMode(ExecutionMode.KUBERNETES)
                        .maxAttempts(maxAttempts)
                        .build())
                .kubernetesSubmission(spec)
                .build();
    }

    private static final class RecordingGateway implements KubernetesSubmissionGateway {

        private int createCalls;
        private Job createdJob;
        private int createConfigMapCalls;
        private ConfigMap createdConfigMap;

        @Override
        public Job create(Job job) {
            createCalls++;
            createdJob = job;
            return job;
        }

        @Override
        public ConfigMap createConfigMap(ConfigMap configMap) {
            createConfigMapCalls++;
            createdConfigMap = configMap;
            return configMap;
        }

        @Override
        public void close() {
            // Recording gateway owns no external client.
        }
    }

    private static final class RecordingUploader implements RuntimeArtifactUploader {

        private int uploadCalls;

        @Override
        public String upload(Path localPath, URI uploadRoot) {
            uploadCalls++;
            return uploadRoot.resolve(localPath.getFileName().toString()).toString();
        }
    }
}
