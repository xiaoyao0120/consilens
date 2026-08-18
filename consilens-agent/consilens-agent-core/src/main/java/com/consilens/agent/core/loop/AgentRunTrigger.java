package com.consilens.agent.core.loop;

/**
 * What started this run. A user message creates a run; secret submission,
 * approval decision and retry create resume runs.
 */
public enum AgentRunTrigger {
    USER_MESSAGE,
    SECRET_PROVIDED,
    APPROVAL_DECIDED,
    RETRY,
    FOLLOW_UP
}
