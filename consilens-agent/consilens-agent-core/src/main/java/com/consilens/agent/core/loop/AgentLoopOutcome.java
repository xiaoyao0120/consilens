package com.consilens.agent.core.loop;

public enum AgentLoopOutcome {
    COMPLETED,
    SUSPENDED,
    FAILED,
    CANCELLED,
    BUDGET_EXHAUSTED,
    TIMEOUT,
    BUSY,
    SESSION_NOT_FOUND
}
