package com.consilens.cluster.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Typed YARN submission settings. The payload is limited to references and resource
 * capabilities and never carries connector configuration or passwords.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YarnSubmissionSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Pattern JAVA_CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
    private static final Pattern YARN_QUEUE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private String runtimeArchiveUri;

    private String descriptorUri;

    private String secretEnvironmentUri;

    private String stagingUri;

    private String amMainClass;

    private String applicationName;

    private String queue;

    private Integer amMemoryMb;

    private Integer amVCores;

    private List<String> tags;

    public void validate() {
        requireArtifactUri("runtimeArchiveUri", runtimeArchiveUri);
        requireArtifactUri("descriptorUri", descriptorUri);
        requireDescriptorFormat(descriptorUri);
        if (secretEnvironmentUri != null) {
            requireArtifactUri("secretEnvironmentUri", secretEnvironmentUri);
        }
        if (stagingUri != null) {
            requireRemoteUri("stagingUri", stagingUri);
        }
        if (isBlank(amMainClass) || !JAVA_CLASS_NAME.matcher(amMainClass).matches()) {
            throw new IllegalArgumentException("amMainClass must be a valid Java class name");
        }
        if (isBlank(applicationName)) {
            throw new IllegalArgumentException("applicationName is required for YARN submission");
        }
        if (queue != null && !YARN_QUEUE_NAME.matcher(queue).matches()) {
            throw new IllegalArgumentException("queue must match [A-Za-z0-9_.-]+ when provided");
        }
        if (amMemoryMb == null || amMemoryMb <= 0) {
            throw new IllegalArgumentException("amMemoryMb must be a positive integer for YARN submission");
        }
        if (amVCores == null || amVCores <= 0) {
            throw new IllegalArgumentException("amVCores must be a positive integer for YARN submission");
        }
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.trim().isEmpty()) {
                    throw new IllegalArgumentException("YARN application tags must not be empty");
                }
            }
        }
    }

    public List<String> sanitizedTags() {
        if (tags == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(tags);
    }

    private void requireArtifactUri(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " is required for YARN submission");
        }
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(name + " must not contain credentials, query parameters, or fragments");
            }
            if (SubmissionReference.isLocal(uri)) {
                if (isBlank(stagingUri)) {
                    throw new IllegalArgumentException(name + " is a local path and requires stagingUri");
                }
            } else if (!uri.isAbsolute()) {
                throw new IllegalArgumentException(name + " must be an absolute URI or a local path");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be an absolute URI or a local path", e);
        }
    }

    private void requireRemoteUri(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " is required for YARN submission");
        }
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || SubmissionReference.isLocal(uri)) {
                throw new IllegalArgumentException(name + " must be an absolute remote URI");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(name + " must not contain credentials, query parameters, or fragments");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be an absolute remote URI", e);
        }
    }

    private void requireDescriptorFormat(String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String path = URI.create(value).getPath();
        String lowerCasePath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (!(lowerCasePath.endsWith(".yaml") || lowerCasePath.endsWith(".yml") || lowerCasePath.endsWith(".json"))) {
            throw new IllegalArgumentException("descriptorUri must reference a YAML or JSON descriptor");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
