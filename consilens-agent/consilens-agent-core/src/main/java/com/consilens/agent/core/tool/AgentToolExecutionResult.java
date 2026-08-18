package com.consilens.agent.core.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentToolOutcome;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentToolExecutionResult {
    String callId;
    AgentToolOutcome<?> outcome;
    AgentWorkingState newState;
    long durationMillis;
    boolean stateChanged;
}
