package com.consilens.agent.api.event;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.With;
import lombok.Value;

import java.time.Instant;

/**
 * One append-only session event. {@code seq} is assigned inside the same
 * database transaction that persists the event; it is the SSE cursor and the
 * recovery cursor.
 */
@Value
@Builder
@With
public class AgentEvent {
    String eventId;
    String sessionId;
    String runId;
    String turnId;
    long seq;
    AgentEventType type;
    AgentEventVisibility visibility;
    int schemaVersion;
    Instant createdAt;
    JsonNode payload;
}
