package com.consilens.ai.tool;

import com.consilens.connector.api.spi.ConnectorProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Describes a connector for planner clarification and defaults.
 */
public class PlannerDescribeConnectorTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "describe_connector";
    }

    @Override
    public String getDescription() {
        return "Describe connector capabilities and common compare usage.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name").put("type", "string").put("description", "Connector type");
        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        String name = input.path("name").asText("");
        boolean exists = ServiceLoader.load(ConnectorProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(provider -> provider.getType().equalsIgnoreCase(name)
                        || ("postgres".equalsIgnoreCase(name) && "postgresql".equalsIgnoreCase(provider.getType())));
        if (!exists) {
            return ToolResult.failure("Unknown connector: " + name);
        }
        String normalized = "postgres".equalsIgnoreCase(name) ? "postgresql" : name.toLowerCase();
        return ToolResult.success("Described connector " + normalized + ".", Map.of(
                "name", normalized,
                "supportsTable", true,
                "supportsSql", true,
                "commonResourceForms", List.of("db.table", "sql"),
                "notes", List.of("Planner should prefer explicit table or sql resources before asking follow-up questions.")));
    }
}
