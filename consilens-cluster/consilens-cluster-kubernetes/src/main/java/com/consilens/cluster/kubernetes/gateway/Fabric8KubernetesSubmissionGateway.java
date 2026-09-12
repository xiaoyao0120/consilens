package com.consilens.cluster.kubernetes.gateway;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.util.Objects;

/**
 * Production gateway using the standard Fabric8 client configuration chain.
 */
public class Fabric8KubernetesSubmissionGateway implements KubernetesSubmissionGateway {

    private final KubernetesClient client;

    public Fabric8KubernetesSubmissionGateway() {
        this(new KubernetesClientBuilder().build());
    }

    Fabric8KubernetesSubmissionGateway(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Job create(Job job) {
        return client.batch().v1().jobs()
                .inNamespace(job.getMetadata().getNamespace())
                .resource(job)
                .create();
    }

    @Override
    public ConfigMap createConfigMap(ConfigMap configMap) {
        return client.configMaps()
                .inNamespace(configMap.getMetadata().getNamespace())
                .resource(configMap)
                .createOrReplace();
    }

    @Override
    public void close() {
        client.close();
    }
}
