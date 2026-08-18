package com.consilens.agent.core.loop;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentStopDecision {
    AgentStopReason reason;
    String message;
}
