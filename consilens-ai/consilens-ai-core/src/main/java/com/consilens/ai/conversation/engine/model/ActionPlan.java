package com.consilens.ai.conversation.engine.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * Execution plan produced by the planner.
 */
@Value
@Builder(toBuilder = true)
public class ActionPlan {

    String sessionId;
    ActionType actionType;
    String commandName;
    String commandArgument;
    String userInput;
    boolean requiresApproval;
    @Singular("attribute")
    Map<String, Object> attributes;
}
