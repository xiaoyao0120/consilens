package com.consilens.agent.core.state;

import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.SlotSource;

/**
 * Slot merge rules (section 19.3): higher-precedence sources win; equal
 * sources are replaced by the newer value; model-inferred values can never
 * override tool-observed or user-confirmed facts.
 */
public final class AgentSlotMerger {

    private AgentSlotMerger() {
    }

    public static AgentSlotValue merge(AgentSlotValue existing, AgentSlotValue incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        if (incoming.getSource().precedence() > existing.getSource().precedence()) {
            return incoming;
        }
        if (incoming.getSource() == existing.getSource()
                && !incoming.getConfirmedAt().isBefore(existing.getConfirmedAt())) {
            return incoming;
        }
        return existing;
    }

    public static boolean isUserConfirmed(AgentSlotValue slot) {
        return slot != null && slot.getSource() == SlotSource.USER_CONFIRMED;
    }
}
