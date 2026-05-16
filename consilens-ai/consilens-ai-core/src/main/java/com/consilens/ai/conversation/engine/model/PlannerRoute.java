package com.consilens.ai.conversation.engine.model;

/**
 * Runtime-facing route selected by the planner.
 */
public enum PlannerRoute {
    PLAN_CONFIG,
    MODIFY_CONFIG,
    RUN_DIFF,
    DIAGNOSE,
    REPAIR,
    CHAT
}
