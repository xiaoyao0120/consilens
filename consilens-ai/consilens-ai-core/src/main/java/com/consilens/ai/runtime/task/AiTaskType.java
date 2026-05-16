package com.consilens.ai.runtime.task;

/**
 * Task types executed by the AI runtime.
 */
public enum AiTaskType {
    USE_CONFIG,
    PLAN_CONFIG,
    VALIDATE,
    DRY_RUN,
    RUN_DIFF,
    DIAGNOSE,
    REPAIR,
    MEMORY_ADD,
    MEMORY_REMOVE,
    EXPLAIN,
    DOCTOR
}
