package com.consilens.cli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpProtocolServer {

    private final ConsilensMcpToolExecutor executor;
    private final ObjectMapper objectMapper;

    public McpProtocolServer(ConsilensMcpToolExecutor executor, ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> handle(Map<String, Object> request) {
        Object id = request.get("id");
        String method = string(request.get("method"));
        if (id == null && method != null && method.startsWith("notifications/")) {
            return null;
        }
        try {
            Object result;
            if ("initialize".equals(method)) {
                result = initializeResult();
            } else if ("tools/list".equals(method)) {
                result = Map.of("tools", ConsilensMcpToolRegistry.definitions().stream()
                        .map(this::tool)
                        .collect(java.util.stream.Collectors.toList()));
            } else if ("tools/call".equals(method)) {
                result = callTool(params(request));
            } else if ("resources/templates/list".equals(method)) {
                result = resourceTemplates();
            } else if ("resources/read".equals(method)) {
                result = readResource(params(request));
            } else {
                return error(id, -32601, "Method not found: " + method);
            }
            return response(id, result);
        } catch (IllegalArgumentException exception) {
            return error(id, -32602, exception.getMessage());
        } catch (Exception exception) {
            return error(id, -32000, exception.getMessage());
        }
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of());
        capabilities.put("resources", Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("serverInfo", Map.of("name", "consilens-mcp", "version", "0.1-SNAPSHOT"));
        result.put("capabilities", capabilities);
        return result;
    }

    private Map<String, Object> callTool(Map<String, Object> params) throws Exception {
        String name = string(params.get("name"));
        if (!ConsilensMcpToolRegistry.supports(name)) {
            throw new IllegalArgumentException("Unsupported MCP tool: " + name);
        }
        Map<String, Object> arguments = map(params.get("arguments"));
        Map<String, Object> payload = executor.execute(name, arguments);
        return Map.of(
                "isError", false,
                "content", List.of(Map.of(
                        "type", "text",
                        "text", objectMapper.writeValueAsString(payload))));
    }

    private Map<String, Object> readResource(Map<String, Object> params) throws Exception {
        String uri = string(params.get("uri"));
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("Resource uri is required");
        }
        Map<String, Object> payload = executor.readResource(uri);
        return Map.of("contents", List.of(Map.of(
                "uri", uri,
                "mimeType", "application/json",
                "text", objectMapper.writeValueAsString(payload))));
    }

    private Map<String, Object> resourceTemplates() {
        return Map.of("resourceTemplates", List.of(
                resourceTemplate("consilens://artifacts/{artifactId}", "Consilens artifact content"),
                resourceTemplate("consilens://configs/{configId}", "Consilens config artifact content"),
                resourceTemplate("consilens://diagnosis/{diagnosisId}", "Consilens diagnosis artifact content")));
    }

    private Map<String, Object> resourceTemplate(String uriTemplate, String description) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("uriTemplate", uriTemplate);
        resource.put("name", uriTemplate);
        resource.put("description", description);
        resource.put("mimeType", "application/json");
        return resource;
    }

    private Map<String, Object> tool(ConsilensMcpToolRegistry.ToolDefinition definition) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", definition.getName());
        tool.put("description", definition.getDescription());
        tool.put("inputSchema", definition.getInputSchema());
        return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> params(Map<String, Object> request) {
        return map(request.get("params"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) value);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> response(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
