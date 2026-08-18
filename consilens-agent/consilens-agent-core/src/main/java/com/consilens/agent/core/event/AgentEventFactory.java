package com.consilens.agent.core.event;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Creates events with stable schema version and UUID ids. Seq is assigned by
 * the persistence layer inside the append transaction.
 */
public final class AgentEventFactory {

    public static final int EVENT_SCHEMA_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentEventFactory() {
    }

    public static AgentEvent create(AgentEventType type,
                                    AgentEventVisibility visibility,
                                    String sessionId,
                                    String runId,
                                    String turnId,
                                    JsonNode payload) {
        return AgentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .runId(runId)
                .turnId(turnId)
                .seq(0)
                .type(type)
                .visibility(visibility)
                .schemaVersion(EVENT_SCHEMA_VERSION)
                .createdAt(Instant.now())
                .payload(payload == null ? MAPPER.createObjectNode() : payload)
                .build();
    }

    public static ObjectNode payload() {
        return MAPPER.createObjectNode();
    }
}
