package com.consilens.cluster.kubernetes.gateway;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.batch.v1.Job;

/**
 * Boundary around Fabric8 so submission mapping can be tested without an API server.
 */
public interface KubernetesSubmissionGateway extends AutoCloseable {

    Job create(Job job);

    ConfigMap createConfigMap(ConfigMap configMap);

    /**
     * Terminal completion status of the Job ({@code Complete} or
     * {@code Failed}), or empty while the Job is still running or unknown,
     * so the client can block like spark-submit on Kubernetes does.
     */
    default java.util.Optional<String> jobCompletionStatus(String namespace, String jobName) {
        return java.util.Optional.empty();
    }

    @Override
    void close();
}
