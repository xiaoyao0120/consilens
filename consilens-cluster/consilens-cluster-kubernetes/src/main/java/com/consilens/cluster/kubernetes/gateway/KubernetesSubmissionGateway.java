package com.consilens.cluster.kubernetes.gateway;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.batch.v1.Job;

/**
 * Boundary around Fabric8 so submission mapping can be tested without an API server.
 */
public interface KubernetesSubmissionGateway extends AutoCloseable {

    Job create(Job job);

    ConfigMap createConfigMap(ConfigMap configMap);

    @Override
    void close();
}
