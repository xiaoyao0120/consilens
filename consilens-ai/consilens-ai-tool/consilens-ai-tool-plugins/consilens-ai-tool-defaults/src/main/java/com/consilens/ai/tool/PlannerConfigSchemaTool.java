package com.consilens.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Returns planner-facing compare config schema and default assumptions.
 */
public class PlannerConfigSchemaTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "get_config_schema";
    }

    @Override
    public String getDescription() {
        return "Return required and optional slots for compare config planning.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("task").put("type", "string").put("description", "Task name, currently compare");
        schema.putArray("required").add("task");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        String task = input.path("task").asText("compare");
        return ToolResult.success("Returned compare config schema.",
                Map.of(
                        "task", task,
                        "requiredSlots", List.of("sourceType", "targetType", "sourceResource", "targetResource", "keys"),
                        "optionalSlots", List.of("fields", "strategyMode", "algorithm", "maxDifferences", "resultSink"),
                        "defaultAssumptions", List.of(
                                "strategyMode=checksum",
                                "algorithm=xor",
                                "resultSink=console+diff-record")));
    }
}
