package com.consilens.agent.api.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentModelUsage {
    long promptTokens;
    long completionTokens;
    long totalTokens;
}
