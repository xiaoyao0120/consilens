package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.TurnDecision;

/**
 * Plans the next system action for a user turn or explicit command.
 */
public interface TurnPlanner {

    TurnDecision plan(PlannerContext context);
}
