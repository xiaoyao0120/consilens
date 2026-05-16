package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.model.FunctionDefinition;

import java.util.List;
import java.util.Map;

/**
 * Executes planner-visible tools and exposes their function definitions to the LLM backend.
 */
public interface PlannerToolExecutor {

    List<FunctionDefinition> functions();

    String execute(String toolName, Map<String, Object> input, PlannerContext context);
}
