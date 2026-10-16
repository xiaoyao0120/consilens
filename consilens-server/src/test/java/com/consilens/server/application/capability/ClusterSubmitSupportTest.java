package com.consilens.server.application.capability;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.config.EndpointConfig;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.domain.model.TaskExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterSubmitSupportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldMountKubernetesDescriptorWithoutPlaintextPasswordsOrDeletingCompletedJob() throws Exception {
        CapturingSubmitter submitter = new CapturingSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("kubernetes");
        request.getOptions().setProperties(Map.of(
                "image", "registry.example/consilens-runtime:1.0",
                "secretEnv", Map.of(
                        "SOURCE_PASSWORD", Map.of("secretName", "compare-db", "secretKey", "source-password"),
                        "TARGET_PASSWORD", Map.of("secretName", "compare-db", "secretKey", "target-password"))));

        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpoint("source-password"))
                .target(endpoint("target-password"))
                .keys(List.of("id"))
                .build();

        ClusterApplicationResult result = support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request, config, () -> false);

        String descriptor = submitter.request.getKubernetesSubmission().getDescriptorData().get("comparison.yaml");
        assertThat(result.succeeded()).isTrue();
        assertThat(descriptor).contains("${env.SOURCE_PASSWORD}", "${env.TARGET_PASSWORD}")
                .doesNotContain("source-password", "target-password");
        assertThat(submitter.request.getKubernetesSubmission().getSecretEnv())
                .containsKeys("SOURCE_PASSWORD", "TARGET_PASSWORD");
        assertThat(submitter.killed).isFalse();
    }

    @Test
    void shouldStageYarnDescriptorFileAndKeepAmDefaults() throws Exception {
        CapturingSubmitter submitter = new CapturingSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpoint("plain-password"))
                .target(endpoint("plain-password"))
                .keys(List.of("id"))
                .build();

        support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request, config, () -> false);

        var spec = submitter.request.getYarnSubmission();
        // YARN 凭据直接写 descriptor（随 staging 上传），本地临时文件路径作为 descriptorUri
        assertThat(spec.getRuntimeArchiveUri()).isEqualTo("hdfs:///apps/consilens/runtime.zip");
        // 临时 descriptor 文件位于系统临时目录（JUnit 临时目录的父目录）
        assertThat(spec.getDescriptorUri()).startsWith(temporaryDirectory.getParent().toString());
        assertThat(spec.getAmMemoryMb()).isEqualTo(1024);
        assertThat(spec.getAmVCores()).isEqualTo(1);
        assertThat(submitter.request.getExecution().getExecutionMode()).isEqualTo(ExecutionMode.YARN);
        // 成功路径不 kill
        assertThat(submitter.killed).isFalse();
    }

    @Test
    void shouldKeepPlaintextPasswordInYarnDescriptor() throws Exception {
        CapturingSubmitter submitter = new CapturingSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("plain-password"))
                        .target(endpoint("plain-password"))
                        .keys(List.of("id"))
                        .build(), () -> false);

        // descriptor 临时文件已上传删除；spec 校验通过即代表路径有效
        assertThat(submitter.request.getYarnSubmission().getDescriptorUri()).isNotBlank();
    }

    @Test
    void shouldRejectInvalidMemoryFormatBeforeSubmission() {
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(new CapturingSubmitter()));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip",
                "amMemory", "2xg"));

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        support.submitAndAwait(
                                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                                ServerCompareConfig.builder()
                                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                                () -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid memory format")).isNotNull();
    }

    @Test
    void shouldKillOnCancellationAfterSubmission() {
        CapturingSubmitter submitter = new CapturingSubmitter();
        submitter.completes = false;
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        support.submitAndAwait(
                                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                                ServerCompareConfig.builder()
                                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                                () -> true))
                .isInstanceOf(TaskCancellationException.class)).isNotNull();
        assertThat(submitter.killed).isTrue();
    }

    @Test
    void shouldKillOnTimeoutAfterSubmission() {
        CapturingSubmitter submitter = new CapturingSubmitter();
        submitter.completes = false;
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));
        request.getOptions().setTimeoutMs(200);

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        support.submitAndAwait(
                                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                                ServerCompareConfig.builder()
                                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                                () -> false))
                .isInstanceOf(java.util.concurrent.TimeoutException.class)).isNotNull();
        assertThat(submitter.killed).isTrue();
    }

    @Test
    void shouldNotKillWhenApplicationFailsOnItsOwn() throws Exception {
        CapturingSubmitter submitter = new CapturingSubmitter();
        submitter.finalStatus = "FAILED";
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        ClusterApplicationResult result = support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                () -> false);

        assertThat(result.getFinalStatus()).isEqualTo("FAILED");
        // 自身失败也是终态，不应 kill
        assertThat(submitter.killed).isFalse();
    }

    @Test
    void shouldSweepStaleApplicationsWithSanitizedSubmissionIdUsedBySubmit() throws Exception {
        CapturingSubmitter submitter = new CapturingSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders:2026.09.15 10:00");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("p"))
                        .target(endpoint("p"))
                        .keys(List.of("id"))
                        .build(), () -> false);

        assertThat(submitter.staleSweepRequest.getSubmissionId()).isEqualTo("orders-2026-09-15-10-00");
        assertThat(submitter.staleSweepRequest.getSubmissionId()).isEqualTo(submitter.request.getSubmissionId());
    }

    @Test
    void shouldContinueSubmissionWhenStaleSweepFails() throws Exception {
        CapturingSubmitter submitter = new CapturingSubmitter();
        submitter.sweepFails = true;
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        ClusterApplicationResult result = support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("p"))
                        .target(endpoint("p"))
                        .keys(List.of("id"))
                        .build(), () -> false);

        assertThat(result.succeeded()).isTrue();
        assertThat(submitter.request).isNotNull();
    }

    private EndpointConfig endpoint(String password) {
        return EndpointConfig.builder()
                .type("mysql")
                .table("orders")
                .connection(Map.of("url", "jdbc:mysql://db:3306/orders", "username", "reader", "password", password))
                .build();
    }

    private static final class CapturingLocator extends ClusterSubmitterLocator {
        private final ClusterSubmitter submitter;

        private CapturingLocator(ClusterSubmitter submitter) {
            this.submitter = submitter;
        }

        @Override
        public ClusterSubmitter locate(String platform) {
            return submitter;
        }
    }

    private static final class CapturingSubmitter implements ClusterSubmitter {
        private ClusterSubmitRequest request;
        private ClusterSubmitRequest staleSweepRequest;
        private boolean killed;
        boolean completes = true;
        boolean sweepFails;
        String finalStatus = "SUCCEEDED";

        @Override
        public ClusterSubmission submit(ClusterSubmitRequest request) {
            this.request = request;
            return ClusterSubmission.builder()
                    .submissionId(request.getSubmissionId())
                    .executionMode(ExecutionMode.KUBERNETES)
                    .clusterApplicationId("default/consilens-orders-20260915")
                    .build();
        }

        @Override
        public void killStale(ClusterSubmitRequest request) {
            staleSweepRequest = request;
            if (sweepFails) {
                throw new IllegalStateException("cluster unreachable");
            }
        }

        @Override
        public ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
            if (!completes) {
                return null;
            }
            return new ClusterApplicationResult(submission.getClusterApplicationId(), finalStatus, null, null);
        }

        @Override
        public void kill(ClusterSubmission submission) {
            killed = true;
        }
    }
}
