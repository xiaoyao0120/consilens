package com.consilens.agent.api.event;

/**
 * Visibility flags. USER_AND_MODEL covers events that both sides must see;
 * AUDIT_ONLY events are never placed into the model context.
 */
public enum AgentEventVisibility {
    USER_VISIBLE(true, false),
    MODEL_VISIBLE(false, true),
    USER_AND_MODEL(true, true),
    AUDIT_ONLY(false, false);

    private final boolean visibleToUser;
    private final boolean visibleToModel;

    AgentEventVisibility(boolean visibleToUser, boolean visibleToModel) {
        this.visibleToUser = visibleToUser;
        this.visibleToModel = visibleToModel;
    }

    public boolean visibleToUser() {
        return visibleToUser;
    }

    public boolean visibleToModel() {
        return visibleToModel;
    }
}
