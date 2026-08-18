package com.consilens.agent.core.tool;

import com.consilens.agent.api.tool.AgentTool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit tool registry: only tools registered here are visible to the model
 * and executable by the loop. No auto-discovery of arbitrary classes.
 */
public final class AgentToolRegistry {

    private final Map<String, AgentTool<?, ?>> tools = new LinkedHashMap<>();

    public AgentToolRegistry(Collection<? extends AgentTool<?, ?>> tools) {
        for (AgentTool<?, ?> tool : tools) {
            if (this.tools.putIfAbsent(tool.descriptor().getName(), tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + tool.descriptor().getName());
            }
        }
    }

    public Optional<AgentTool<?, ?>> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<AgentTool<?, ?>> all() {
        return List.copyOf(tools.values());
    }
}
