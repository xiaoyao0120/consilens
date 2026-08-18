package com.consilens.agent.core.loop;

import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentLoopResult {
    AgentLoopOutcome outcome;
    AgentSessionStatus sessionStatus;
    AgentRunStatus runStatus;
    long lastSeq;
    String errorCode;
    boolean retryable;
}
