package com.consilens.ai.session.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Persisted approval request for a conversation session.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = PendingApprovalState.PendingApprovalStateBuilder.class)
public class PendingApprovalState {

    String type;
    String prompt;
    String commandName;
    String commandArgument;
    Instant createdAt;

    @JsonPOJOBuilder(withPrefix = "")
    public static class PendingApprovalStateBuilder {
    }
}
