package com.consilens.cli.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP tool catalog for Consilens tool-first runtime.
 */
public final class ConsilensMcpToolRegistry {

    public static final class ToolDefinition {
        private final String name;
        private final String description;
        private final String httpMethod;
        private final String apiPath;
        private final Map<String, Object> inputSchema;

        private ToolDefinition(String name,
                               String description,
                               String httpMethod,
                               String apiPath,
                               Map<String, Object> inputSchema) {
            this.name = name;
            this.description = description;
            this.httpMethod = httpMethod;
            this.apiPath = apiPath;
            this.inputSchema = Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public String getHttpMethod() {
            return httpMethod;
        }

        public String getApiPath() {
            return apiPath;
        }

        public Map<String, Object> getInputSchema() {
            return inputSchema;
        }
    }

    private static final Map<String, ToolDefinition> TOOLS;

    static {
        Map<String, ToolDefinition> tools = new LinkedHashMap<>();
        put(tools, "consilens.plan.config", "Build a compare config artifact.", "POST", "/v1/plan",
                schema(List.of("goal", "source", "target", "keys"),
                        props(
                                prop("goal", "string", "Compare goal."),
                                prop("source", "object", "Source endpoint."),
                                prop("target", "object", "Target endpoint."),
                                prop("keys", "array", "Primary key fields."),
                                prop("hints", "object", "Planning hints."))));
        put(tools, "consilens.validate.config", "Validate a config artifact or inline config.", "POST", "/v1/validate",
                schema(List.of(),
                        props(
                                prop("configArtifactId", "string", "CONFIG artifact id."),
                                prop("configContent", "object", "Inline config content."),
                                prop("options", "object", "Validation options."))));
        put(tools, "consilens.run.diff", "Submit an asynchronous diff run.", "POST", "/v1/run",
                schema(List.of(),
                        props(
                                prop("configArtifactId", "string", "CONFIG artifact id."),
                                prop("configId", "string", "Alias for configArtifactId."),
                                prop("serialNo", "string", "Caller-provided idempotency key."),
                                prop("dryRun", "boolean", "Run without source/target connections."),
                                prop("timeoutMs", "integer", "Run timeout in milliseconds."),
                                prop("options", "object", "Run options."))));
        put(tools, "consilens.diagnose.diff", "Diagnose a run result artifact.", "POST", "/v1/diagnose",
                schema(List.of(),
                        props(
                                prop("runArtifactId", "string", "RUN_RESULT artifact id."),
                                prop("artifactId", "string", "Alias for runArtifactId."),
                                prop("options", "object", "Diagnosis options."))));
        put(tools, "consilens.repair.config", "Generate a repair config from diagnosis.", "POST", "/v1/repair",
                schema(List.of(),
                        props(
                                prop("diagnosisArtifactId", "string", "DIAGNOSIS artifact id."),
                                prop("diagnosisId", "string", "Alias for diagnosisArtifactId."),
                                prop("artifactId", "string", "Alias for diagnosisArtifactId."),
                                prop("options", "object", "Repair options."))));
        put(tools, "consilens.save.config", "Save a config artifact to a local file.", "LOCAL", "file",
                schema(List.of("path"),
                        props(
                                prop("artifactId", "string", "CONFIG artifact id."),
                                prop("configArtifactId", "string", "Alias for artifactId."),
                                prop("configId", "string", "Alias for artifactId."),
                                prop("path", "string", "Destination file path."))));
        put(tools, "consilens.get.artifact", "Fetch artifact metadata and optional content.", "GET", "/v1/artifacts/{artifactId}",
                schema(List.of(),
                        props(
                                prop("artifactId", "string", "Artifact id."),
                                prop("includeContent", "boolean", "Whether to include artifact content."))));
        TOOLS = Collections.unmodifiableMap(tools);
    }

    private ConsilensMcpToolRegistry() {
    }

    public static Set<String> tools() {
        LinkedHashSet<String> tools = new LinkedHashSet<>(TOOLS.keySet());
        return Set.copyOf(tools);
    }

    public static List<ToolDefinition> definitions() {
        return List.copyOf(TOOLS.values());
    }

    public static Optional<ToolDefinition> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(TOOLS.get(toolName));
    }

    public static boolean supports(String toolName) {
        return toolName != null && TOOLS.containsKey(toolName);
    }

    private static void put(Map<String, ToolDefinition> tools,
                            String name,
                            String description,
                            String httpMethod,
                            String apiPath,
                            Map<String, Object> inputSchema) {
        tools.put(name, new ToolDefinition(name, description, httpMethod, apiPath, inputSchema));
    }

    private static Map<String, Object> schema(List<String> required, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", required);
        schema.put("properties", properties);
        schema.put("additionalProperties", true);
        return schema;
    }

    @SafeVarargs
    private static Map<String, Object> props(Map.Entry<String, Object>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private static Map.Entry<String, Object> prop(String name, String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return Map.entry(name, property);
    }
}
