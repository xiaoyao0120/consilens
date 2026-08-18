package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentSecretResolver;
import com.consilens.agent.api.tool.AgentToolContext;

/**
 * Minimal tool context for unit tests.
 */
public final class DefaultTestToolContext implements AgentToolContext {

    private final String actorId;
    private final String sessionId;
    private final AgentWorkingState workingState;

    public DefaultTestToolContext(String actorId, String sessionId) {
        this(actorId, sessionId, AgentWorkingState.builder()
                .objectiveId("o1").objective("obj").build());
    }

    public DefaultTestToolContext(String actorId, String sessionId, AgentWorkingState workingState) {
        this.actorId = actorId;
        this.sessionId = sessionId;
        this.workingState = workingState;
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
        return "run-test";
    }

    @Override
    public String turnId() {
        return "turn-test";
    }

    @Override
    public String objectiveId() {
        return "obj-1";
    }

    @Override
    public AgentWorkingState workingState() {
        return workingState;
    }

    @Override
    public AgentSecretResolver secretResolver() {
        return new AgentSecretResolver() {
            @Override
            public char[] resolve(String secretRequestId) {
                return new char[0];
            }

            @Override
            public void consume(String secretRequestId) {
            }
        };
    }
}
