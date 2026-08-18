package com.consilens.agent.core.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentSecretResolver;
import com.consilens.agent.api.tool.AgentToolContext;

import java.util.Set;

public final class DefaultAgentToolContext implements AgentToolContext {

    private final String actorId;
    private final String sessionId;
    private final String runId;
    private final String turnId;
    private final String objectiveId;
    private final AgentWorkingState workingState;
    private final AgentSecretResolver secretResolver;
    private final Set<String> permissions;

    public DefaultAgentToolContext(String actorId,
                                   String sessionId,
                                   String runId,
                                   String turnId,
                                   String objectiveId,
                                   AgentWorkingState workingState,
                                   AgentSecretResolver secretResolver,
                                   Set<String> permissions) {
        this.actorId = actorId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.turnId = turnId;
        this.objectiveId = objectiveId;
        this.workingState = workingState;
        this.secretResolver = secretResolver;
        this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    @Override
    public String actorId() {
        return actorId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public String runId() {
        return runId;
    }

    @Override
    public String turnId() {
        return turnId;
    }

    @Override
    public String objectiveId() {
        return objectiveId;
    }

    @Override
    public AgentWorkingState workingState() {
        return workingState;
    }

    @Override
    public AgentSecretResolver secretResolver() {
        return secretResolver;
    }

    public Set<String> permissions() {
        return permissions;
    }
}
