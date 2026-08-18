package com.consilens.agent.api.state;

import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

import java.time.Instant;

/**
 * One draft slot: value plus provenance. Slots never carry secrets; secret
 * slots exist only as references to out-of-band secret requests.
 */
@Value
@Builder
@Jacksonized
public class AgentSlotValue {
    Object value;
    SlotSource source;
    double confidence;
    Instant confirmedAt;

    public static AgentSlotValue of(Object value, SlotSource source) {
        return AgentSlotValue.builder()
                .value(value)
                .source(source)
                .confidence(source == SlotSource.USER_CONFIRMED ? 1.0 : 0.6)
                .confirmedAt(Instant.now())
                .build();
    }
}
