package com.consilens.agent.api.state;

/**
 * Fixed precedence: USER_CONFIRMED > TOOL_OBSERVED > USER_INFERRED >
 * SYSTEM_DEFAULT > MODEL_INFERRED. Confidence affects whether we ask again;
 * it never influences permissions or approvals.
 */
public enum SlotSource {
    USER_CONFIRMED(5),
    TOOL_OBSERVED(4),
    USER_INFERRED(3),
    SYSTEM_DEFAULT(2),
    MODEL_INFERRED(1);

    private final int precedence;

    SlotSource(int precedence) {
        this.precedence = precedence;
    }

    public int precedence() {
        return precedence;
    }

    public boolean canOverride(SlotSource existing) {
        return precedence() > existing.precedence();
    }
}
