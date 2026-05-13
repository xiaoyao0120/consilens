package com.consilens.ai.runtime.model;

import lombok.Builder;
import lombok.Value;

/**
 * Output of a single conversation/runtime turn.
 */
@Value
@Builder
public class AiTurnResult {

    public enum Status {
        COMPLETED,
        REQUIRES_APPROVAL,
        REQUIRES_CLARIFICATION,
        FAILED
    }

    Status status;
    String message;
    String suggestedTask;
}
