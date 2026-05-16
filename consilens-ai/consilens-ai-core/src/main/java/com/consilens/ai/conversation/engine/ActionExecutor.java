package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.runtime.model.AiTaskResult;

/**
 * Executes planned actions using underlying runtime capabilities.
 */
public interface ActionExecutor {

    AiTaskResult execute(ActionPlan plan);
}
