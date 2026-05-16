package com.consilens.ai.conversation.engine.model;

import com.consilens.ai.session.model.AiSession;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * Planner input context.
 */
@Value
@Builder
public class PlannerContext {

    AiSession session;
    String rawInput;
    String commandName;
    String commandArgument;
    @Singular("attribute")
    Map<String, Object> attributes;
}
