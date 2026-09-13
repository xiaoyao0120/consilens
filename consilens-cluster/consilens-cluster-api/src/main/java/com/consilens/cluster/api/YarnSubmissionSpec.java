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

    /**
     * Coordinator launched inside the ApplicationMaster; an implementation
     * detail that is not user-selectable, so a blank value is defaulted here
     * rather than exposed as a CLI option.
     */
    public static final String DEFAULT_AM_MAIN_CLASS =
            "com.consilens.cluster.application.ClusterComparisonCoordinator";

    private String runtimeArchiveUri;

    private String descriptorUri;

    private String stagingUri;

    private String amMainClass;

    private String applicationName;

    private String queue;

    private Integer amMemoryMb;

    private Integer amVCores;

    private List<String> tags;

    /**
     * Extra files localized into the AM container working directory and
     * reachable by their file name, mirroring spark-submit {@code --files}.
     * Entries accept an optional {@code #alias} suffix; remote URIs are used
     * in place, local paths are staged first.
     */
    private List<String> files;

    /**
     * Extra jars localized into the AM container and appended to the AM
     * classpath after the runtime archive, mirroring spark-submit
     * {@code --jars}.
     */
    private List<String> jars;

    public void validate() {
        requireArtifactUri("runtimeArchiveUri", runtimeArchiveUri);
        requireArtifactUri("descriptorUri", descriptorUri);
        requireDescriptorFormat(descriptorUri);
        if (stagingUri != null) {
            requireRemoteUri("stagingUri", stagingUri);
        }
        validateReferences(files, "files");
        validateReferences(jars, "jars");
        if (isBlank(amMainClass)) {
            amMainClass = DEFAULT_AM_MAIN_CLASS;
        }
        if (!JAVA_CLASS_NAME.matcher(amMainClass).matches()) {
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

    /**
     * URI part of a {@code uri#alias} reference (or the entry itself).
     */
    public static String referenceUri(String reference) {
        int separator = reference.indexOf('#');
        return separator >= 0 ? reference.substring(0, separator) : reference;
    }

    /**
     * Alias part of a {@code uri#alias} reference, or null when absent.
     */
    public static String referenceAlias(String reference) {
        int separator = reference.indexOf('#');
        return separator >= 0 ? reference.substring(separator + 1) : null;
    }

    private void validateReferences(List<String> references, String name) {
        if (references == null) {
            return;
        }
        for (String reference : references) {
            if (isBlank(reference)) {
                throw new IllegalArgumentException(name + " entries must not be blank");
            }
            String alias = referenceAlias(reference);
            if (alias != null && isBlank(alias)) {
                throw new IllegalArgumentException(name + " entry has a blank alias: " + reference);
            }
            String uriPart = referenceUri(reference);
            URI uri;
            try {
                uri = URI.create(uriPart);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(name + " entry is not a valid URI or local path: " + uriPart, e);
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException(name + " must not contain credentials or query parameters");
            }
            if (!SubmissionReference.isLocal(uri) && !uri.isAbsolute()) {
                throw new IllegalArgumentException(name + " entry must be a local path or absolute URI: " + uriPart);
            }
            String destinationName = alias != null ? alias : baseName(uriPart);
            if (isBlank(destinationName)) {
                throw new IllegalArgumentException(name + " entry has no file name: " + uriPart);
            }
            if (RESERVED_DESTINATION_NAMES.contains(destinationName) || destinationName.startsWith("submission-descriptor.")) {
                throw new IllegalArgumentException(name + " entry overlaps a reserved localization name: " + destinationName);
            }
        }
    }

    private String baseName(String uriPart) {
        String path = URI.create(uriPart).getPath();
        if (path == null) {
            return "";
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static final java.util.Set<String> RESERVED_DESTINATION_NAMES = java.util.Set.of(
            "consilens-runtime", "consilens-runtime.jar", "submission-secrets.properties", "hadoop-conf");

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
                // A missing stagingUri is acceptable here: the submitter falls
                // back to the user's HDFS home staging directory, the same way
                // spark-submit stages under spark.yarn.stagingDir by default.
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
