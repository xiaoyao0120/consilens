package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Transport view of persisted memory items.
 */
@Value
@Builder
public class MemoryEntryDto {

    String id;
    String type;
    String content;
    String source;
    Instant createdAt;
}
