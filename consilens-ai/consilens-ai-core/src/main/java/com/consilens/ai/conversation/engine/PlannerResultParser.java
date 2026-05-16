package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerResult;

/**
 * Parses raw planner JSON into a structured planner result.
 */
public interface PlannerResultParser {

    PlannerResult parse(String payload);
}
