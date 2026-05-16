package com.consilens.ai.tool;

import com.consilens.ai.runtime.intent.CompareIntentHintExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Deterministically extracts compare hints from natural-language input.
 */
public class PlannerExtractCompareHintsTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "extract_compare_hints";
    }

    @Override
    public String getDescription() {
        return "Extract deterministic compare hints from natural language as non-authoritative planner hints.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("text").put("type", "string").put("description", "Natural-language compare request");
        schema.putArray("required").add("text");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        String text = input.path("text").asText("");
        Map<String, String> hints = CompareIntentHintExtractor.inferHints(text);
        return ToolResult.success("Extracted compare hints.", hints);
    }
}
