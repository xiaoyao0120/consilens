package com.consilens.cluster.kubernetes.gateway;

import java.net.URI;
import java.nio.file.Path;

/**
 * Uploads a local runtime artifact to a shared endpoint before a Kubernetes Job
 * is created.
 */
public interface RuntimeArtifactUploader extends AutoCloseable {

    String upload(Path localPath, URI uploadRoot);

    @Override
    default void close() {
        // Most uploaders are stateless.
    }
}
