package com.consilens.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes current session state to the planner.
 */
public class PlannerSessionStateTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "get_session_state";
    }

    @Override
    public String getDescription() {
        return "Return current conversation session state and artifact availability.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("sessionId").put("type", "string");
        schema.putArray("required").add("sessionId");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        Map<String, Object> attrs = context.getAttributes() == null ? Map.of() : context.getAttributes();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", input.path("sessionId").asText(""));
        copy(attrs, payload, "status", "currentTask", "currentObjective", "currentConfigArtifactId",
                "latestRunArtifactId", "latestDiagnosisArtifactId", "latestAuditArtifactId");
        payload.put("hasConfig", truthy(attrs.get("currentConfigArtifactId")));
        payload.put("hasRun", truthy(attrs.get("latestRunArtifactId")));
        payload.put("hasDiagnosis", truthy(attrs.get("latestDiagnosisArtifactId")));
        payload.put("hasAudit", truthy(attrs.get("latestAuditArtifactId")));
        return ToolResult.success("Returned session state.", payload);
    }

    private void copy(Map<String, Object> attrs, Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (attrs.containsKey(key)) {
                payload.put(key, attrs.get(key));
            }
        }
    }

    private boolean truthy(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }
}
