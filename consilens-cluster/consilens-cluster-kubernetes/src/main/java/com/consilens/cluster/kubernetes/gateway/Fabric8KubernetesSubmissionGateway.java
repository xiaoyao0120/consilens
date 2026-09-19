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
    public java.util.Optional<String> jobCompletionStatus(String namespace, String jobName) {
        Job job = client.batch().v1().jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .get();
        if (job == null || job.getStatus() == null || job.getStatus().getConditions() == null) {
            return java.util.Optional.empty();
        }
        return job.getStatus().getConditions().stream()
                .filter(condition -> Boolean.parseBoolean(condition.getStatus()))
                .filter(condition -> "Complete".equals(condition.getType())
                        || "Failed".equals(condition.getType()))
                .map(io.fabric8.kubernetes.api.model.batch.v1.JobCondition::getType)
                .findFirst();
    }

    @Override
    public void deleteJob(String namespace, String jobName) {
        client.batch().v1().jobs().inNamespace(namespace).withName(jobName).delete();
    }

    @Override
    public void close() {
        client.close();
    }
}
