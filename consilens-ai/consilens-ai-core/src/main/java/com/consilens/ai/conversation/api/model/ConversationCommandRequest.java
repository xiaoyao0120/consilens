package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

/**
 * Transport-neutral command request for deterministic actions.
 */
@Value
@Builder
public class ConversationCommandRequest {

    String sessionId;
    String commandName;
    String argument;
    @Singular("attribute")
    Map<String, Object> attributes;
}
