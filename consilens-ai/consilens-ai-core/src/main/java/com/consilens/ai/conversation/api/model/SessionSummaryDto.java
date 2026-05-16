package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Summary item for session listings.
 */
@Value
@Builder
public class SessionSummaryDto {

    String sessionId;
    String summary;
    String currentObjective;
    String status;
    String currentTask;
    Instant lastActiveAt;
}
