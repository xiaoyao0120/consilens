package com.consilens.agent.api.state;

/**
 * Lifecycle status of a session. WAITING_* statuses belong to sessions;
 * {@code WAITING} belongs to a run and must not be mixed in the same column.
 */
public enum AgentSessionStatus {
    READY,
    RUNNING,
    WAITING_INPUT,
    WAITING_SECRET,
    WAITING_APPROVAL,
    FAILED,
    CANCELLED,
    ARCHIVED
}
