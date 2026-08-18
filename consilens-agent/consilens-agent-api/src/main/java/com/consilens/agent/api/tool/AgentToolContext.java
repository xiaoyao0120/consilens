package com.consilens.agent.api.tool;

import com.consilens.agent.api.state.AgentWorkingState;

public interface AgentToolContext {

    String actorId();

    String sessionId();

    String runId();

    String turnId();

    String objectiveId();

    AgentWorkingState workingState();

    AgentSecretResolver secretResolver();
}
