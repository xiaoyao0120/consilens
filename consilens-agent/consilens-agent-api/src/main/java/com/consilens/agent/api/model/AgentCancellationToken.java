package com.consilens.agent.api.model;

/**
 * Cooperative cancellation passed to the model client and to long-running
 * tool execution. Implementations must be thread-safe.
 */
public interface AgentCancellationToken {

    boolean isCancelled();

    AgentCancellationToken NEVER_CANCELLED = () -> false;
}
