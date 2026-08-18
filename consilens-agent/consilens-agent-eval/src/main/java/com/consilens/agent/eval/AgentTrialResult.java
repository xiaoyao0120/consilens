package com.consilens.agent.eval;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.state.AgentSessionStatus;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AgentTrialResult {
    String scenarioId;
    AgentSessionStatus finalSessionStatus;
    List<AgentEvent> events;
    List<String> executedToolNames;
    boolean cancelled;
}
