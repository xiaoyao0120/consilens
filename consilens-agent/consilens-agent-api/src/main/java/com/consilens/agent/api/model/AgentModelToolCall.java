package com.consilens.agent.api.model;

import lombok.Builder;
import lombok.Value;

/**
 * One tool call proposed by the model. {@code arguments} is raw JSON text; the
 * core validates it against the registered tool schema before execution.
 */
@Value
@Builder
public class AgentModelToolCall {
    String id;
    String name;
    String arguments;
    int index;
}
