package com.consilens.agent.core.loop;

public enum AgentStopReason {
    TASK_COMPLETE,
    WAITING_INPUT,
    WAITING_SECRET,
    WAITING_APPROVAL,
    PLAIN_ANSWER
}
