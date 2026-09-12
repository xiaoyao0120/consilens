package com.consilens.cluster.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed Kubernetes Job submission settings. It carries runtime references,
 * resource capabilities and optional descriptor content where secrets are
 * represented as environment placeholders, never as plaintext passwords.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KubernetesSubmissionSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final Pattern JAVA_CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("\\S+");
    private static final Pattern LABEL_NAME = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9_.-]*[A-Za-z0-9])?");
    private static final Pattern LABEL_VALUE = Pattern.compile("([A-Za-z0-9]([A-Za-z0-9_.-]*[A-Za-z0-9])?)?");
    private static final Pattern DNS_SUBDOMAIN = Pattern.compile(
            "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SECRET_KEY = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern DESCRIPTOR_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> IMAGE_PULL_POLICIES = Set.of("Always", "IfNotPresent", "Never");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{env\\.[A-Za-z0-9_]+}");
    private static final Pattern SAFE_RUNTIME_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar");
    private static final Set<String> SECRET_KEY_NAMES = Set.of(
            "password", "passwd", "pwd", "secret", "secretkey", "secret-key", "secret_key",
            "token", "apikey", "api-key", "api_key", "accesskey", "access-key", "access_key",
            "privatekey", "private-key", "private_key", "credential", "credentials");

    private String namespace;

    private String jobName;

    private String image;

    private String descriptorUri;

    private Map<String, String> descriptorData;

    private String descriptorConfigMapName;

    private String descriptorMountPath;

    private String localRuntimePath;

    private String runtimeUploadUrl;

    private String runtimeDownloadUrl;

    private String initContainerImage;

    private String coordinatorMainClass;

    private Integer memoryMiB;

    private Integer cpuMilli;

    private String serviceAccountName;

    private String imagePullPolicy;

    private Map<String, String> labels;

    private Map<String, KubernetesSecretKeyRef> secretEnv;

    public void validate() {
        requireDnsLabel("namespace", namespace);
        requireDnsLabel("jobName", jobName);
        if (isBlank(image) || !IMAGE_REFERENCE.matcher(image).matches()) {
            throw new IllegalArgumentException("image must be a non-blank container image reference");
        }
        validateDescriptorSource();
        if (isBlank(coordinatorMainClass) || !JAVA_CLASS_NAME.matcher(coordinatorMainClass).matches()) {
            throw new IllegalArgumentException("coordinatorMainClass must be a valid Java class name");
        }
        if (memoryMiB == null || memoryMiB <= 0) {
            throw new IllegalArgumentException("memoryMiB must be a positive integer for Kubernetes submission");
        }
        if (cpuMilli == null || cpuMilli <= 0) {
            throw new IllegalArgumentException("cpuMilli must be a positive integer for Kubernetes submission");
        }
        if (serviceAccountName != null) {
            requireDnsLabel("serviceAccountName", serviceAccountName);
        }
        validateRuntimeSource();
        if (imagePullPolicy != null && !IMAGE_PULL_POLICIES.contains(imagePullPolicy)) {
            throw new IllegalArgumentException("imagePullPolicy must be Always, IfNotPresent, or Never");
        }
        if (labels != null) {
            labels.forEach((key, value) -> {
                if (!isValidLabelKey(key)) {
                    throw new IllegalArgumentException("Kubernetes label keys must be valid qualified label names");
                }
                if (value == null || value.length() > 63 || !LABEL_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("Kubernetes label values must be valid label values");
                }
            });
        }
        if (secretEnv != null) {
            secretEnv.forEach((environmentName, secretRef) -> {
                if (environmentName == null || !ENVIRONMENT_NAME.matcher(environmentName).matches()) {
                    throw new IllegalArgumentException("Kubernetes secret environment names must be valid environment names");
                }
                if (secretRef == null) {
                    throw new IllegalArgumentException("Kubernetes secret environment reference is required");
                }
                requireDnsLabel("secretName", secretRef.getSecretName());
                if (isBlank(secretRef.getSecretKey()) || !SECRET_KEY.matcher(secretRef.getSecretKey()).matches()) {
                    throw new IllegalArgumentException("secretKey must contain only letters, digits, '.', '_' or '-'");
                }
            });
        }
    }

    public Map<String, String> sanitizedLabels() {
        return labels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(labels);
    }

    public Map<String, KubernetesSecretKeyRef> sanitizedSecretEnv() {
        return secretEnv == null ? new LinkedHashMap<>() : new LinkedHashMap<>(secretEnv);
    }

    public Map<String, String> sanitizedDescriptorData() {
        return descriptorData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(descriptorData);
    }

    private void validateDescriptorSource() {
        boolean hasData = descriptorData != null && !descriptorData.isEmpty();
        if (hasData) {
            if (descriptorData.size() != 1) {
                throw new IllegalArgumentException("descriptorData must contain exactly one descriptor file");
            }
            if (isBlank(descriptorConfigMapName) || !DNS_LABEL.matcher(descriptorConfigMapName).matches()) {
                throw new IllegalArgumentException("descriptorConfigMapName must be a Kubernetes DNS label");
            }
            if (isBlank(descriptorMountPath)) {
                throw new IllegalArgumentException("descriptorMountPath is required for ConfigMap descriptor");
            }
            descriptorData.forEach((name, content) -> {
                if (isBlank(name) || !DESCRIPTOR_FILE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("descriptorData names must be simple file names");
                }
                if (content == null) {
                    throw new IllegalArgumentException("descriptorData content is required");
                }
                requireEnvironmentPlaceholderSecrets(name, content);
            });
        } else {
            requireHttpDescriptorUri(descriptorUri);
        }
    }

    private void requireEnvironmentPlaceholderSecrets(String name, String content) {
        JsonNode root = descriptorTree(name, content);
        requireSensitiveValuesUsePlaceholders(root);
    }

    private JsonNode descriptorTree(String name, String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("descriptorData content is required");
        }
        try {
            if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                return new ObjectMapper().readTree(content);
            }
            return new ObjectMapper(new YAMLFactory()).readTree(content);
        } catch (IOException e) {
            throw new IllegalArgumentException("descriptorData must be valid YAML or JSON", e);
        }
    }

    private void requireSensitiveValuesUsePlaceholders(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if (SECRET_KEY_NAMES.contains(key.toLowerCase(Locale.ROOT))) {
                    requireEnvironmentPlaceholder(value);
                }
                requireSensitiveValuesUsePlaceholders(value);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                requireSensitiveValuesUsePlaceholders(child);
            }
        }
    }

    private void requireEnvironmentPlaceholder(JsonNode value) {
        String scalar = value.isValueNode() ? value.asText() : value.toString();
        String normalized = stripQuotes(scalar.trim());
        if (!ENV_PLACEHOLDER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "descriptorData contains a plaintext secret; replace it with ${env.NAME} and bind the value through --secret-env");
        }
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private void validateRuntimeSource() {
        boolean hasLocalRuntime = localRuntimePath != null && !localRuntimePath.trim().isEmpty();
        if (hasLocalRuntime) {
            String fileName = localRuntimePath.substring(localRuntimePath.lastIndexOf('/') + 1);
            if (!SAFE_RUNTIME_FILE_NAME.matcher(fileName).matches()) {
                throw new IllegalArgumentException("localRuntimePath must reference a safe fat jar file name");
            }
            if (isBlank(runtimeUploadUrl) || isBlank(runtimeDownloadUrl)) {
                throw new IllegalArgumentException("runtimeUploadUrl and runtimeDownloadUrl are required for a local runtime");
            }
            if (isBlank(initContainerImage)) {
                throw new IllegalArgumentException("initContainerImage is required for local runtime download");
            }
            requireRemoteArtifactUrl("runtimeUploadUrl", runtimeUploadUrl);
            requireRemoteArtifactUrl("runtimeDownloadUrl", runtimeDownloadUrl);
        }
    }

    private void requireRemoteArtifactUrl(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " is required for Kubernetes submission");
        }
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException(name + " must be an absolute remote URL");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(name + " must not contain credentials, query parameters, or fragments");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be an absolute remote URL", e);
        }
    }

    private void requireDnsLabel(String name, String value) {
        if (isBlank(value) || value.length() > 63 || !DNS_LABEL.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase Kubernetes DNS label up to 63 characters");
        }
    }

    private void requireHttpDescriptorUri(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("descriptorUri is required for Kubernetes submission");
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("descriptorUri must be an HTTP(S) secret-free URI");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("descriptorUri must be an HTTP(S) secret-free URI", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isValidLabelKey(String key) {
        if (key == null) {
            return false;
        }
        int separator = key.indexOf('/');
        if (separator < 0) {
            return isValidLabelName(key);
        }
        if (separator == 0 || separator != key.lastIndexOf('/') || separator == key.length() - 1) {
            return false;
        }
        String prefix = key.substring(0, separator);
        return prefix.length() <= 253 && DNS_SUBDOMAIN.matcher(prefix).matches()
                && isValidLabelName(key.substring(separator + 1));
    }

    private boolean isValidLabelName(String value) {
        return value.length() <= 63 && LABEL_NAME.matcher(value).matches();
    }
}
