package com.consilens.ai.tool;

import com.consilens.connector.api.spi.ConnectorProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Lists available connector providers for planner routing.
 */
public class PlannerListConnectorsTool implements Tool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "list_connectors";
    }

    @Override
    public String getDescription() {
        return "List available connector types and aliases.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = OBJECT_MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        List<Map<String, Object>> connectors = ServiceLoader.load(ConnectorProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .map(provider -> Map.<String, Object>of(
                        "name", provider.getType(),
                        "aliases", "postgresql".equals(provider.getType())
                                ? List.of("postgres", "postgresql")
                                : List.of(provider.getType())))
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.get("name"))))
                .collect(Collectors.toList());
        return ToolResult.success("Listed connectors.", Map.of("connectors", connectors));
    }
}
