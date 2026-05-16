package com.consilens.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Returns planner-visible memory facts from the execution context.
 */
public class PlannerMemoryFactsTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "get_memory_facts";
    }

    @Override
    public String getDescription() {
        return "Return non-sensitive memory facts relevant to the current session.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("sessionId").put("type", "string");
        properties.putObject("limit").put("type", "integer");
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
        Object facts = attrs.get("memoryFacts");
        return ToolResult.success("Returned memory facts.", Map.of(
                "facts", facts instanceof List ? facts : List.of()));
    }
}
