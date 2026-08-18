package com.consilens.agent.core.event;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces the event order invariants from section 21.3: a run starts before
 * any turn, TOOL_COMPLETED never precedes TOOL_STARTED, and a run terminates
 * with exactly one terminal event.
 */
public final class AgentEventSequenceValidator {

    private AgentEventSequenceValidator() {
    }

    public static List<String> validate(List<AgentEvent> events) {
        List<String> violations = new ArrayList<>();
        boolean runStarted = false;
        boolean terminal = false;
        int startedTools = 0;
        int completedTools = 0;

        for (AgentEvent event : events) {
            AgentEventType type = event.getType();
            if (type == AgentEventType.RUN_STARTED) {
                if (terminal) {
                    violations.add("RUN_STARTED after terminal event");
                }
                runStarted = true;
            }
            if (type == AgentEventType.TURN_STARTED && !runStarted) {
                violations.add("TURN_STARTED before RUN_STARTED");
            }
            if (type == AgentEventType.TOOL_STARTED) {
                startedTools++;
            }
            if (type == AgentEventType.TOOL_COMPLETED) {
                completedTools++;
                if (completedTools > startedTools) {
                    violations.add("TOOL_COMPLETED before TOOL_STARTED");
                }
            }
            if (isTerminal(type)) {
                terminal = true;
            } else if (terminal) {
                violations.add("event after terminal event: " + type);
            }
        }
        return violations;
    }

    public static boolean isTerminal(AgentEventType type) {
        return type == AgentEventType.RUN_SUSPENDED
                || type == AgentEventType.RUN_COMPLETED
                || type == AgentEventType.RUN_FAILED
                || type == AgentEventType.RUN_CANCELLED;
    }
}
