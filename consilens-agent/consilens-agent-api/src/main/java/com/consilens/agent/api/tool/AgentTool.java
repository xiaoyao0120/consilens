package com.consilens.agent.api.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Canonical typed tool. Model JSON is validated against {@link #inputSchema()}
 * and converted to {@code I} before execution; state only changes through the
 * deterministic {@link #reduce(AgentWorkingState, Object)}.
 */
public interface AgentTool<I, O> {

    AgentToolDescriptor descriptor();

    Class<I> inputType();

    JsonNode inputSchema();

    AgentToolOutcome<O> execute(I input, AgentToolContext context);

    AgentWorkingState reduce(AgentWorkingState previous, O output);
}
