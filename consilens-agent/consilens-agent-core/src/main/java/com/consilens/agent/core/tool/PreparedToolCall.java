package com.consilens.agent.core.tool;

import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PreparedToolCall {
    AgentModelToolCall modelCall;
    AgentTool<?, ?> tool;
    Object input;
    JsonNode canonicalArgs;
    String argsDigest;
    String idempotencyKey;
    String actionDigest;
}
