package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

/**
 * Structured conversation event returned to REPL/API clients.
 */
@Value
@Builder
public class ConversationEventDto {

    String stage;
    String status;
    String message;
    String artifactId;
    String artifactType;
    Map<String, String> metadata;
}
