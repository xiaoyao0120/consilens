package com.consilens.agent.api.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AgentModelRequest {
    String provider;
    String model;
    String baseUrl;
    String apiKey;
    List<AgentModelMessage> messages;
    List<AgentModelToolDefinition> toolDefinitions;
    Double temperature;
    Integer maxTokens;
    long timeoutMillis;
}
