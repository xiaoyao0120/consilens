package com.consilens.ai.session.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * Persisted clarification question for a conversation session.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = PendingQuestionState.PendingQuestionStateBuilder.class)
public class PendingQuestionState {

    String question;
    String originalRequest;
    @Singular("expectedKey")
    List<String> expectedKeys;
    boolean blocking;
    Instant createdAt;

    @JsonPOJOBuilder(withPrefix = "")
    public static class PendingQuestionStateBuilder {
    }
}
