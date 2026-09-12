package com.consilens.cluster.yarn;

import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.cluster.yarn.gateway.YarnSubmissionGateway;
import com.consilens.connector.api.planner.ExecutionMode;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.util.Records;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YarnClusterSubmitterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldMapCompleteSubmissionContextThroughGateway() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/consilens-runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/submission-descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens-compare-orders")
                .queue("data-quality")
                .amMemoryMb(2048)
                .amVCores(2)
                .tags(List.of("consilens", "compare"))
                .build());

        ClusterSubmission submission = new YarnClusterSubmitter(gateway).submit(request);

        ApplicationSubmissionContext context = gateway.submittedContext;
        assertEquals("consilens-compare-orders", context.getApplicationName());
        assertEquals("data-quality", context.getQueue());
        assertEquals(2048, context.getResource().getMemory());
        assertEquals(2, context.getResource().getVirtualCores());
        assertEquals(2, context.getMaxAppAttempts());
        assertEquals(Set.of("consilens", "compare"), context.getApplicationTags());

        Map<String, LocalResource> localResources = context.getAMContainerSpec().getLocalResources();
        assertEquals(Set.of("consilens-runtime", "submission-descriptor.json"), localResources.keySet());
        assertEquals(LocalResourceType.ARCHIVE, localResources.get("consilens-runtime").getType());
        assertEquals(LocalResourceVisibility.APPLICATION, localResources.get("consilens-runtime").getVisibility());
        assertEquals("/apps/consilens/consilens-runtime.zip",
                localResources.get("consilens-runtime").getResource().getFile());
        assertEquals(LocalResourceType.FILE, localResources.get("submission-descriptor.json").getType());

        List<String> commands = context.getAMContainerSpec().getCommands();
        assertEquals(1, commands.size());
        String command = commands.get(0);
        assertTrue(command.contains("-Xmx2048m"));
        assertTrue(command.contains("-cp consilens-runtime/*"));
        assertTrue(command.contains("com.consilens.am.ConsilensApplicationMaster"));
        assertTrue(command.contains("submission-yarn-1"));
        assertTrue(command.contains("submission-descriptor.json"));
        assertFalse(command.contains("password"));

        assertEquals(context.getApplicationId().toString(), submission.getClusterApplicationId());
        assertEquals(ExecutionMode.YARN, submission.getExecutionMode());
        assertTrue(gateway.createCalls >= 1);
        assertEquals(1, gateway.submitCalls);

        Map<String, String> amEnvironment = context.getAMContainerSpec().getEnvironment();
        assertEquals("yarn", amEnvironment.get("CONSILENS_AM_REPORTER"));
        assertEquals("resourcemanager", amEnvironment.get("CONSILENS_AM_YARN_RM_HOSTNAME"));
    }

    @Test
    void shouldPreserveDescriptorFormatAndLocalizeProtectedSecretEnvironment() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setDescriptorUri("hdfs://namenode/apps/consilens/submission.yaml");
        spec.setSecretEnvironmentUri("hdfs://namenode/apps/consilens/submission-secrets.properties");

        new YarnClusterSubmitter(gateway).submit(yarnRequest(spec));

        ContainerLaunchContext context = gateway.submittedContext.getAMContainerSpec();
        assertTrue(context.getLocalResources().containsKey("submission-descriptor.yaml"));
        assertTrue(context.getLocalResources().containsKey("submission-secrets.properties"));
        assertTrue(context.getCommands().get(0).endsWith("submission-descriptor.yaml submission-secrets.properties"));
    }

    @Test
    void shouldRejectNonYarnModeBeforeContactingGateway() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = ClusterSubmitRequest.builder()
                .submissionId("submission-local-1")
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("datasource/source-orders")
                        .targetConfigRef("datasource/target-orders")
                        .build())
                .execution(ClusterExecutionDescription.builder().executionMode(ExecutionMode.LOCAL).build())
                .yarnSubmission(validYarnSpec())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway).submit(request));

        assertTrue(error.getMessage().contains("only supports YARN"));
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.submitCalls);
    }

    @Test
    void shouldRejectMissingYarnSpecBeforeContactingGateway() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway).submit(request));

        assertTrue(error.getMessage().contains("yarnSubmission is required"));
        assertEquals(0, gateway.submitCalls);
    }

    @Test
    void shouldRejectInvalidReferenceAndResourceParametersBeforeContactingGateway() {
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("relative/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("ping -p")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(0)
                .amVCores(1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(-1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://user:password@namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
    }

    @Test
    void shouldRejectNonPositiveMaxAttemptsBeforeContactingGateway() {
        assertRejectedAttemptsBeforeGateway(0);
        assertRejectedAttemptsBeforeGateway(-1);
    }

    @Test
    void shouldRejectUnsafeSubmissionIdUsedInAmCommand() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(validYarnSpec());
        request.setSubmissionId("unsafe id; rm -rf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway).submit(request));

        assertTrue(error.getMessage().contains("submissionId"));
        assertEquals(0, gateway.submitCalls);
    }

    @Test
    void shouldUseDefaultsForQueueAndMissingMaxAttempts() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
        request.getExecution().setMaxAttempts(null);

        new YarnClusterSubmitter(gateway).submit(request);

        assertEquals("default", gateway.submittedContext.getQueue());
        assertEquals(1, gateway.submittedContext.getMaxAppAttempts());
    }

    @Test
    void shouldNotLeakTagOrQueueSecretsIntoCommand() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .tags(List.of("secret-tag-abc"))
                .build());

        new YarnClusterSubmitter(gateway).submit(request);

        String command = gateway.submittedContext.getAMContainerSpec().getCommands().get(0);
        assertFalse(command.contains("secret-tag-abc"));
    }

    @Test
    void shouldStageLocalRuntimeAndDescriptorBeforeSubmission() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        Path runtime = temporaryDirectory.resolve("runtime.zip");
        Path descriptor = temporaryDirectory.resolve("descriptor.json");
        Files.write(runtime, new byte[]{1, 2, 3});
        Files.write(descriptor, new byte[]{4, 5, 6});
        YarnSubmissionSpec spec = YarnSubmissionSpec.builder()
                .runtimeArchiveUri(runtime.toString())
                .descriptorUri(descriptor.toString())
                .stagingUri("hdfs://namenode/apps/staging/consilens")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build();

        ClusterSubmission submission = new YarnClusterSubmitter(gateway).submit(yarnRequest(spec));

        assertEquals(2, gateway.stagedLocals.size());
        assertTrue(gateway.stagedLocals.get(0).toString().startsWith("file:"));
        assertTrue(gateway.stagedLocals.get(1).toString().startsWith("file:"));
        Map<String, LocalResource> resources = gateway.submittedContext.getAMContainerSpec().getLocalResources();
        assertTrue(resources.get("consilens-runtime").getResource().getFile().startsWith("/apps/staging/consilens/"));
        assertTrue(resources.get("submission-descriptor.json").getResource().getFile().startsWith("/apps/staging/consilens/"));
        assertEquals("submission-yarn-1", submission.getSubmissionId());
    }

    @Test
    void shouldTreatJarRuntimeAsFileLocalResource() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setRuntimeArchiveUri("hdfs://namenode/apps/consilens/consilens-runtime.jar");

        new YarnClusterSubmitter(gateway).submit(yarnRequest(spec));

        Map<String, LocalResource> resources = gateway.submittedContext.getAMContainerSpec().getLocalResources();
        assertEquals(LocalResourceType.FILE, resources.get("consilens-runtime.jar").getType());
        assertTrue(gateway.submittedContext.getAMContainerSpec().getCommands().get(0).contains("-cp consilens-runtime.jar"));
        assertFalse(gateway.submittedContext.getAMContainerSpec().getCommands().get(0).contains("consilens-runtime/*"));
    }

    private void assertRejectedBeforeGateway(YarnSubmissionSpec spec) {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway).submit(yarnRequest(spec)));
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.submitCalls);
    }

    private void assertRejectedAttemptsBeforeGateway(int maxAttempts) {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(validYarnSpec());
        request.getExecution().setMaxAttempts(maxAttempts);

        assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway).submit(request));
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.submitCalls);
    }

    private YarnSubmissionSpec validYarnSpec() {
        return YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.am.ConsilensApplicationMaster")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build();
    }

    private ClusterSubmitRequest yarnRequest(YarnSubmissionSpec spec) {
        return ClusterSubmitRequest.builder()
                .submissionId("submission-yarn-1")
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("datasource/source-orders")
                        .targetConfigRef("datasource/target-orders")
                        .build())
                .execution(ClusterExecutionDescription.builder()
                        .executionMode(ExecutionMode.YARN)
                        .maxAttempts(2)
                        .build())
                .yarnSubmission(spec)
                .build();
    }

    private static final class RecordingYarnGateway implements YarnSubmissionGateway {

        private int createCalls;
        private int submitCalls;
        private ApplicationSubmissionContext submittedContext;
        private final List<URI> stagedLocals = new ArrayList<>();

        @Override
        public ApplicationSubmissionContext createSubmissionContext() {
            createCalls++;
            ApplicationSubmissionContext context = Records.newRecord(ApplicationSubmissionContext.class);
            context.setApplicationId(ApplicationId.newInstance(0L, 42));
            return context;
        }

        @Override
        public LocalResource createLocalResource(URI resourceUri, LocalResourceType type) {
            URL url = URL.fromURI(resourceUri);
            return LocalResource.newInstance(url, type, LocalResourceVisibility.APPLICATION, 0L, 0L);
        }

        @Override
        public URI stageLocalFile(URI localUri, URI stagingBase) {
            stagedLocals.add(localUri);
            String fileName = URI.create(localUri.toString()).getPath();
            int separator = fileName.lastIndexOf('/');
            String normalizedBase = stagingBase.toString().endsWith("/") ? stagingBase.toString() : stagingBase.toString() + "/";
            return URI.create(normalizedBase).resolve((separator >= 0 ? fileName.substring(separator + 1) : fileName));
        }

        @Override
        public ApplicationId submit(ApplicationSubmissionContext context) {
            submitCalls++;
            submittedContext = context;
            return context.getApplicationId();
        }

        @Override
        public String resourceManagerHostname() {
            return "resourcemanager";
        }

        @Override
        public void close() {
            // Recording gateway holds no external resource.
        }
    }
}
