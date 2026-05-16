package com.consilens.ai.conversation.engine;

/**
 * Shared planner-related context keys used across planner and runtime layers.
 */
public final class PlannerRuntimeContextKeys {

    public static final String CONFIG_REQUEST = "configRequest";
    public static final String PLANNER_RESULT = "plannerResult";
    public static final String PLANNER_ROUTE = "plannerRoute";
    public static final String PLANNER_ASSUMPTIONS = "plannerAssumptions";
    public static final String PLANNER_MISSING_SLOTS = "plannerMissingSlots";

    private PlannerRuntimeContextKeys() {
    }
}
