package com.consilens.ai.session.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Stored non-sensitive memory item.
 */
@Value
@Builder
@JsonDeserialize(builder = AiMemory.AiMemoryBuilder.class)
public class AiMemory {

    String memoryId;
    String type;
    String content;
    String source;
    @Builder.Default
    Instant createdAt = Instant.EPOCH;

    @JsonPOJOBuilder(withPrefix = "")
    public static class AiMemoryBuilder {
    }
}
