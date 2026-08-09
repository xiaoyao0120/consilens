package com.consilens.ai.conversation.engine.model;

import lombok.Builder;
import lombok.Value;

/**
 * Planner output.
 */
@Value
@Builder
public class TurnDecision {

    public enum Type {
        QUESTION,
        ACTION,
        MESSAGE,
        ERROR
    }

    Type type;
    QuestionSpec question;
    ActionPlan actionPlan;
    String message;
}
