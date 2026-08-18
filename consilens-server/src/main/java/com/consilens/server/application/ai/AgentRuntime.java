package com.consilens.server.application.ai;

import com.consilens.agent.api.model.AgentModelClient;
import com.consilens.agent.core.loop.AgentRunConfig;
import com.consilens.agent.core.loop.DefaultAgentLoop;
import com.consilens.agent.core.tool.AgentToolRegistry;

/**
 * Assembled agent runtime handed to application services and controllers.
 * Created only when {@code consilens.server.ai.enabled=true}.
 */
public final class AgentRuntime {

    private final AgentToolRegistry registry;
    private final DefaultAgentLoop loop;
    private final AgentModelClient modelClient;
    private final AgentRunConfig runConfig;

    public AgentRuntime(AgentToolRegistry registry,
                        DefaultAgentLoop loop,
                        AgentModelClient modelClient,
                        AgentRunConfig runConfig) {
        this.registry = registry;
        this.loop = loop;
        this.modelClient = modelClient;
        this.runConfig = runConfig;
    }

    public AgentToolRegistry registry() {
        return registry;
    }

    public DefaultAgentLoop loop() {
        return loop;
    }

    public AgentModelClient modelClient() {
        return modelClient;
    }

    public AgentRunConfig runConfig() {
        return runConfig;
    }
}
