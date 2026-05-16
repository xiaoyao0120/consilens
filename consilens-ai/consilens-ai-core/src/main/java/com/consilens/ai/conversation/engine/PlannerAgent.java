package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.PlannerResult;

/**
 * High-level planner abstraction that classifies a turn before runtime execution.
 */
public interface PlannerAgent {

    PlannerResult plan(PlannerContext context);
}
