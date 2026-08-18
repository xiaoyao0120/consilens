package com.consilens.agent.api.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentModelToolDefinition {
    String name;
    String description;
    JsonNode inputSchema;
}
