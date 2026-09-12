package com.consilens.cluster.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SubmissionSpecValidationTest {

    @Test
    void shouldRequireStagingUriForLocalYarnArtifacts() {
        YarnSubmissionSpec spec = YarnSubmissionSpec.builder()
                .runtimeArchiveUri("runtime.zip")
                .descriptorUri("comparison.yaml")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build();

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldRequireEveryLocalRuntimeFieldForKubernetes() {
        KubernetesSubmissionSpec spec = KubernetesSubmissionSpec.builder()
                .namespace("default")
                .jobName("consilens")
                .image("registry.example/consilens:1.0.0")
                .descriptorUri("https://configs.example/consilens/comparison.yaml")
                .localRuntimePath("runtime.jar")
                .runtimeUploadUrl("https://artifact.example/consilens")
                .runtimeDownloadUrl("https://artifact.example/consilens")
                .coordinatorMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .memoryMiB(1024)
                .cpuMilli(500)
                .build();

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldRejectUnsafeKubernetesRuntimeFileName() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setLocalRuntimePath("consilens-runtime-$(id).jar");
        spec.setRuntimeUploadUrl("https://artifact.example/consilens");
        spec.setRuntimeDownloadUrl("https://artifact.example/consilens");
        spec.setInitContainerImage("curlimages/curl:8.4.0");

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldRejectMultipleKubernetesDescriptorFiles() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("source.yaml", "source: {}");
        descriptorData.put("target.yaml", "target: {}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldRejectPlaintextJsonDescriptorSecret() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json", "{\n  \"source\": {\n    \"password\": \"real-password\"\n  }\n}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldAcceptJsonDescriptorWithEnvironmentPlaceholder() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json",
                "{\n  \"source\": {\n    \"password\": \"${env.SOURCE_PASSWORD}\"\n  }\n}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        spec.validate();
    }

    @Test
    void shouldRejectCompactJsonDescriptorSecret() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json", "{\"source\":{\"password\":\"real-password\"}}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldAcceptCompactJsonDescriptorWithEnvironmentPlaceholder() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.json", "{\"source\":{\"password\":\"${env.SOURCE_PASSWORD}\"}}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        spec.validate();
    }

    @Test
    void shouldRejectYamlFlowStylePlaintextSecret() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.yaml", "{source: {password: real-password}}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        assertThrows(IllegalArgumentException.class, spec::validate);
    }

    @Test
    void shouldAcceptYamlFlowStyleEnvironmentPlaceholder() {
        KubernetesSubmissionSpec spec = validKubernetesSpec();
        spec.setDescriptorUri(null);
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.yaml", "{source: {password: \"${env.SOURCE_PASSWORD}\"}}");
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        spec.validate();
    }

    private KubernetesSubmissionSpec validKubernetesSpec() {
        return KubernetesSubmissionSpec.builder()
                .namespace("default")
                .jobName("consilens")
                .image("registry.example/consilens:1.0.0")
                .descriptorUri("https://configs.example/consilens/comparison.yaml")
                .coordinatorMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .memoryMiB(1024)
                .cpuMilli(500)
                .build();
    }
}
