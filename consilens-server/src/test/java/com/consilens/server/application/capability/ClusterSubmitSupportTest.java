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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterSubmitSupportTest {

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
        private boolean killed;

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
        public ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
            return new ClusterApplicationResult(submission.getClusterApplicationId(), "SUCCEEDED", null, null);
        }

        @Override
        public void kill(ClusterSubmission submission) {
            killed = true;
        }
    }
}
