package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * DTO view of persisted approval state.
 */
@Value
@Builder
public class PendingApprovalDto {

    String type;
    String prompt;
    String relatedCommandName;
    String relatedCommandArgument;
    Instant createdAt;
}
