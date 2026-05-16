package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * DTO view of persisted clarification state.
 */
@Value
@Builder
public class PendingQuestionDto {

    String question;
    @Singular("expectedKey")
    List<String> expectedKeys;
    boolean blocking;
    String originalRequest;
    Instant createdAt;
}
