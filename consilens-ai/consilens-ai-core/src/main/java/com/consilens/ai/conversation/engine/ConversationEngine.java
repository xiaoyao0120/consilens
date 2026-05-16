package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationResponse;

import java.util.Map;

/**
 * Stateful conversation engine behind REPL and high-level API.
 */
public interface ConversationEngine {

    ConversationResponse handleUserTurn(String sessionId, String input);

    default ConversationResponse handleUserTurn(String sessionId, String input, Map<String, Object> attributes) {
        return handleUserTurn(sessionId, input);
    }

    ConversationResponse executeCommand(ConversationCommandRequest request);

    ConversationResponse approve(String sessionId);

    ConversationResponse deny(String sessionId);
}
