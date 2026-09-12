package com.consilens.cluster.kubernetes.gateway;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Uploads a local runtime artifact with a streaming HTTP PUT request.
 */
public class HttpRuntimeArtifactUploader implements RuntimeArtifactUploader {

    @Override
    public String upload(Path localPath, URI uploadRoot) {
        Path normalized = localPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("local runtime is not a regular file: " + normalized);
        }
        String fileName = normalized.getFileName().toString();
        URI target = directoryUri(uploadRoot).resolve(fileName);
        try {
            URL url = target.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            try (java.io.OutputStream output = connection.getOutputStream()) {
                Files.copy(normalized, output);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException("runtime upload failed with HTTP " + responseCode);
            }
            connection.disconnect();
            return target.toString();
        } catch (Exception e) {
            throw new IllegalStateException("unable to upload local runtime " + normalized, e);
        }
    }

    private URI directoryUri(URI uri) {
        String value = uri.toString();
        return value.endsWith("/") ? uri : URI.create(value + "/");
    }
}
