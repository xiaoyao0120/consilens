package com.consilens.agent.api.state;

public enum AgentRunStatus {
    QUEUED,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
