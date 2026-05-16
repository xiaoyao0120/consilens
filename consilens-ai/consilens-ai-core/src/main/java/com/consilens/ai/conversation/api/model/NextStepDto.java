package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

/**
 * Suggested next action surfaced to clients.
 */
@Value
@Builder
public class NextStepDto {

    String code;
    String description;
}
