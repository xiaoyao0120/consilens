package com.consilens.mcp.server;

import com.consilens.mcp.client.ConsilensServerClient;
import com.consilens.mcp.client.ConsilensServerException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConsilensToolExecutor {

    private final ConsilensServerClient client;
    private final McpJsonMapper jsonMapper;

    public ConsilensToolExecutor(ConsilensServerClient client, McpJsonMapper jsonMapper) {
        this.client = client;
        this.jsonMapper = jsonMapper;
    }

    public McpSchema.CallToolResult execute(String name, Map<String, Object> arguments) {
        try {
            Map<String, Object> result = doExecute(name, new LinkedHashMap<>(arguments == null ? Map.of() : arguments));
            return toolResult(false, result);
        } catch (ConsilensServerException | IllegalArgumentException exception) {
            return toolResult(true, errorPayload(exception));
        }
    }

    private Map<String, Object> doExecute(String name, Map<String, Object> arguments) {
        return switch (name) {
            case "consilens.plan.config" -> client.post("/v1/plan", arguments);
            case "consilens.validate.config" -> client.post("/v1/validate", arguments);
            case "consilens.run.diff" -> runDiff(arguments);
            case "consilens.diagnose.diff" -> client.post("/v1/diagnose", arguments);
            case "consilens.repair.config" -> client.post("/v1/repair", arguments);
            case "consilens.get.artifact" -> getArtifact(arguments);
            default -> throw new IllegalArgumentException("Unsupported MCP tool: " + name);
        };
    }

    private Map<String, Object> runDiff(Map<String, Object> arguments) {
        requireString(arguments, "serialNo");
        requireString(arguments, "configArtifactId");
        return client.post("/v1/run", arguments);
    }

    private Map<String, Object> getArtifact(Map<String, Object> arguments) {
        String artifactId = requireString(arguments, "artifactId");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifact", client.getArtifact(artifactId));
        if (Boolean.TRUE.equals(arguments.get("includeContent"))) {
            result.put("content", client.getArtifactContent(artifactId));
        }
        return result;
    }

    private String requireString(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return String.valueOf(value);
    }

    private McpSchema.CallToolResult toolResult(boolean error, Map<String, Object> payload) {
        return new McpSchema.CallToolResult(
                java.util.List.of(new McpSchema.TextContent(writeJson(payload))),
                error,
                payload,
                null);
    }

    private Map<String, Object> errorPayload(Exception exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", exception.getMessage());
        if (exception instanceof ConsilensServerException serverException) {
            payload.put("errorCode", serverException.getErrorCode());
            payload.put("statusCode", serverException.getStatusCode());
            if (serverException.getTraceId() != null) {
                payload.put("traceId", serverException.getTraceId());
            }
        } else {
            payload.put("errorCode", "INVALID_INPUT");
        }
        return payload;
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize MCP result", exception);
        }
    }
}
