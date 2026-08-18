package com.consilens.agent.api;

import com.consilens.agent.api.state.SlotSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotSourceTest {

    @Test
    void precedenceIsFixedAndNeverChanges() {
        assertTrue(SlotSource.USER_CONFIRMED.canOverride(SlotSource.TOOL_OBSERVED));
        assertTrue(SlotSource.TOOL_OBSERVED.canOverride(SlotSource.USER_INFERRED));
        assertTrue(SlotSource.USER_INFERRED.canOverride(SlotSource.SYSTEM_DEFAULT));
        assertTrue(SlotSource.SYSTEM_DEFAULT.canOverride(SlotSource.MODEL_INFERRED));
        assertFalse(SlotSource.MODEL_INFERRED.canOverride(SlotSource.USER_CONFIRMED));
        assertFalse(SlotSource.TOOL_OBSERVED.canOverride(SlotSource.USER_CONFIRMED));
    }
}
