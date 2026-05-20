package com.consilens.cli.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConsilensServerMcpToolExecutor implements ConsilensMcpToolExecutor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final String serverUrl;
    private final String authToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ConsilensServerMcpToolExecutor(String serverUrl, String authToken, ObjectMapper objectMapper) {
        this(serverUrl, authToken, objectMapper, HttpClient.newHttpClient());
    }

    ConsilensServerMcpToolExecutor(String serverUrl,
                                   String authToken,
                                   ObjectMapper objectMapper,
                                   HttpClient httpClient) {
        this.serverUrl = trimTrailingSlash(serverUrl == null || serverUrl.isBlank()
                ? "http://127.0.0.1:18080"
                : serverUrl);
        this.authToken = authToken == null || authToken.isBlank() ? null : authToken.trim();
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public Map<String, Object> execute(String toolName, Map<String, Object> arguments) throws Exception {
        if ("consilens.save.config".equals(toolName)) {
            return saveConfig(arguments);
        }
        if ("consilens.get.artifact".equals(toolName)) {
            return getArtifact(arguments);
        }
        ConsilensMcpToolRegistry.ToolDefinition tool = ConsilensMcpToolRegistry.find(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported MCP tool: " + toolName));
        return post(tool.getApiPath(), requestBody(toolName, arguments));
    }

    @Override
    public Map<String, Object> readResource(String uri) throws Exception {
        String prefix = "consilens://";
        if (uri == null || !uri.startsWith(prefix)) {
            throw new IllegalArgumentException("Unsupported resource uri: " + uri);
        }
        String[] parts = uri.substring(prefix.length()).split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Unsupported resource uri: " + uri);
        }
        String artifactId = parts[1];
        return get("/v1/artifacts/" + encode(artifactId) + "/content");
    }

    private Map<String, Object> getArtifact(Map<String, Object> arguments) throws Exception {
        String artifactId = firstString(arguments, "artifactId", "configId", "diagnosisId");
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId is required");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifact", get("/v1/artifacts/" + encode(artifactId)));
        if (Boolean.TRUE.equals(arguments.get("includeContent"))) {
            result.put("content", get("/v1/artifacts/" + encode(artifactId) + "/content"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> saveConfig(Map<String, Object> arguments) throws Exception {
        String artifactId = firstString(arguments, "artifactId", "configArtifactId", "configId");
        String path = firstString(arguments, "path");
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId is required");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Map<String, Object> response = get("/v1/artifacts/" + encode(artifactId) + "/content");
        Object data = response.get("data");
        String content = data instanceof Map ? String.valueOf(((Map<String, Object>) data).get("content")) : "";
        Path output = Path.of(path);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, content, StandardCharsets.UTF_8);
        return Map.of("artifactId", artifactId, "path", output.toString(), "bytes", content.getBytes(StandardCharsets.UTF_8).length);
    }

    private Map<String, Object> requestBody(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        if ("consilens.run.diff".equals(toolName)) {
            alias(body, "configArtifactId", "configId");
            Map<String, Object> options = map(body.remove("options"));
            moveIfPresent(body, options, "dryRun");
            moveIfPresent(body, options, "timeoutMs");
            body.put("options", options);
            if (!body.containsKey("serialNo")) {
                body.put("serialNo", "mcp_" + sha256(arguments));
            }
        } else if ("consilens.diagnose.diff".equals(toolName)) {
            alias(body, "runArtifactId", "artifactId");
        } else if ("consilens.repair.config".equals(toolName)) {
            alias(body, "diagnosisArtifactId", "diagnosisId");
            alias(body, "diagnosisArtifactId", "artifactId");
        }
        return body;
    }

    private Map<String, Object> post(String path, Map<String, Object> body) throws Exception {
        HttpRequest.Builder builder = baseRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        return send(builder);
    }

    private Map<String, Object> get(String path) throws Exception {
        return send(baseRequest(path).GET());
    }

    private Map<String, Object> send(HttpRequest.Builder builder) throws Exception {
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> body = response.body() == null || response.body().isBlank()
                ? new LinkedHashMap<>()
                : objectMapper.readValue(response.body(), MAP_TYPE);
        body.putIfAbsent("httpStatus", response.statusCode());
        return body;
    }

    private HttpRequest.Builder baseRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serverUrl + path))
                .header("Accept", "application/json");
        if (authToken != null) {
            builder.header("Authorization", "Bearer " + authToken);
        }
        return builder;
    }

    private void alias(Map<String, Object> body, String target, String source) {
        if (!body.containsKey(target) && body.get(source) != null) {
            body.put(target, body.get(source));
        }
        body.remove(source);
    }

    private void moveIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.remove(key));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return new LinkedHashMap<>();
    }

    private String firstString(Map<String, Object> arguments, String... keys) {
        if (arguments == null) {
            return null;
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String sha256(Object value) throws Exception {
        byte[] bytes = objectMapper.writeValueAsBytes(value == null ? Map.of() : value);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            builder.append(String.format("%02x", item));
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
