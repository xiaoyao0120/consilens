package com.consilens.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Returns the current config content or summary from planner context attributes.
 */
public class PlannerCurrentConfigTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "get_current_config";
    }

    @Override
    public String getDescription() {
        return "Return a summary of the current config for modify and repair planning.";
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
        Object content = attrs.get("currentConfigContent");
        Object summary = attrs.get("currentConfigSummary");
        if (content == null && summary == null) {
            return ToolResult.failure("No current config is available in the planner context.");
        }
        return ToolResult.success("Returned current config summary.", Map.of(
                "summary", summary == null ? "" : summary,
                "content", content == null ? "" : content));
    }
}
