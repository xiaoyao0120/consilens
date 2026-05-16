package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Conversation response returned by the high-level service.
 */
@Value
@Builder
public class ConversationResponse {

    public enum Type {
        MESSAGE,
        QUESTION,
        APPROVAL,
        ERROR
    }

    Type type;
    String message;
    String errorCode;
    NextStepDto suggestedNextStep;
    SessionSnapshot session;
    List<ConversationEventDto> events;
}
