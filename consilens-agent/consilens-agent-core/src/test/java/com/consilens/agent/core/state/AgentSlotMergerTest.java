package com.consilens.agent.core.state;

import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.SlotSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSlotMergerTest {

    @Test
    void higherPrecedenceSourceWins() {
        AgentSlotValue modelInferred = slot("guessed", SlotSource.MODEL_INFERRED);
        AgentSlotValue userConfirmed = slot("real", SlotSource.USER_CONFIRMED);
        assertEquals(userConfirmed, AgentSlotMerger.merge(modelInferred, userConfirmed));
        assertEquals(userConfirmed, AgentSlotMerger.merge(userConfirmed, modelInferred));
    }

    @Test
    void toolObservedCorrectsModelInferredButNotUserChoice() {
        AgentSlotValue model = slot("mysql", SlotSource.MODEL_INFERRED);
        AgentSlotValue observed = slot("starrocks", SlotSource.TOOL_OBSERVED);
        AgentSlotValue user = slot("postgresql", SlotSource.USER_CONFIRMED);
        assertEquals(observed, AgentSlotMerger.merge(model, observed));
        assertEquals(user, AgentSlotMerger.merge(user, observed));
    }

    @Test
    void newerUserValueReplacesOlderUserValue() {
        AgentSlotValue older = AgentSlotValue.builder()
                .value("old-host")
                .source(SlotSource.USER_CONFIRMED)
                .confidence(1.0)
                .confirmedAt(Instant.parse("2026-08-15T08:00:00Z"))
                .build();
        AgentSlotValue newer = AgentSlotValue.builder()
                .value("new-host")
                .source(SlotSource.USER_CONFIRMED)
                .confidence(1.0)
                .confirmedAt(Instant.parse("2026-08-15T09:00:00Z"))
                .build();
        assertEquals(newer, AgentSlotMerger.merge(older, newer));
    }

    @Test
    void systemDefaultDoesNotOverrideUserInferred() {
        AgentSlotValue defaultPort = slot(3306, SlotSource.SYSTEM_DEFAULT);
        AgentSlotValue userPort = slot(3307, SlotSource.USER_INFERRED);
        assertEquals(userPort, AgentSlotMerger.merge(defaultPort, userPort));
    }

    private static AgentSlotValue slot(Object value, SlotSource source) {
        return AgentSlotValue.of(value, source);
    }
}
