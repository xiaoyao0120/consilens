package com.consilens.agent.core.event;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventSequenceValidatorTest {

    private AgentEvent event(AgentEventType type) {
        return AgentEventFactory.create(type, AgentEventVisibility.AUDIT_ONLY, "s1", "run1", null, null);
    }

    @Test
    void acceptsCanonicalToolLoopSequence() {
        List<AgentEvent> events = List.of(
                event(AgentEventType.RUN_STARTED),
                event(AgentEventType.TURN_STARTED),
                event(AgentEventType.ASSISTANT_MESSAGE),
                event(AgentEventType.TOOL_PROPOSED),
                event(AgentEventType.TOOL_STARTED),
                event(AgentEventType.TOOL_COMPLETED),
                event(AgentEventType.TURN_COMPLETED),
                event(AgentEventType.RUN_COMPLETED));
        assertTrue(AgentEventSequenceValidator.validate(events).isEmpty());
    }

    @Test
    void rejectsToolCompletedBeforeToolStarted() {
        List<AgentEvent> events = List.of(
                event(AgentEventType.RUN_STARTED),
                event(AgentEventType.TURN_STARTED),
                event(AgentEventType.TOOL_COMPLETED));
        assertFalse(AgentEventSequenceValidator.validate(events).isEmpty());
    }

    @Test
    void rejectsEventsAfterTerminalEvent() {
        List<AgentEvent> events = List.of(
                event(AgentEventType.RUN_STARTED),
                event(AgentEventType.RUN_COMPLETED),
                event(AgentEventType.TURN_STARTED));
        assertFalse(AgentEventSequenceValidator.validate(events).isEmpty());
    }

    @Test
    void acceptsPlanExecutorSequenceWithoutModelTurns() {
        List<AgentEvent> events = List.of(
                event(AgentEventType.RUN_STARTED),
                event(AgentEventType.PLAN_COMMIT_STARTED),
                event(AgentEventType.PLAN_ACTION_STARTED),
                event(AgentEventType.PLAN_ACTION_COMPLETED),
                event(AgentEventType.PLAN_COMMIT_COMPLETED),
                event(AgentEventType.RUN_COMPLETED));
        assertTrue(AgentEventSequenceValidator.validate(events).isEmpty());
    }
}
