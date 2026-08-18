package com.consilens.agent.api.model;

/**
 * Event sink for model calls. Synchronous providers may only call
 * {@link #onCompleted(AgentModelResponse)}; streaming providers also emit
 * deltas. The core never fabricates deltas for non-streaming providers.
 */
public interface AgentModelEventListener {

    default void onDelta(String delta) {
    }

    default void onToolCallDelta(int index, String name, String argumentsDelta) {
    }

    default void onCompleted(AgentModelResponse response) {
    }
}
