package com.consilens.agent.api.state;

/**
 * Business progress of the objective, advanced only by deterministic reducers
 * or the approved plan executor, never by model text.
 */
public enum AgentWorkflowStage {
    DISCOVERY,
    COLLECTING_DATASOURCES,
    VALIDATING_CONNECTIONS,
    DISCOVERING_METADATA,
    DRAFTING_COMPARISON,
    PLAN_READY,
    AWAITING_COMMIT_APPROVAL,
    COMMITTING,
    RESOURCES_READY,
    COMPLETED
}
