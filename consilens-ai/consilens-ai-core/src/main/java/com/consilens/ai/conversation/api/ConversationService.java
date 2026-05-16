package com.consilens.ai.conversation.api;

import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.api.model.CurrentConfigResponse;
import com.consilens.ai.conversation.api.model.MemoryEntryDto;
import com.consilens.ai.conversation.api.model.SessionRecoveryResponse;
import com.consilens.ai.conversation.api.model.SaveConfigResponse;
import com.consilens.ai.conversation.api.model.SessionSnapshot;
import com.consilens.ai.conversation.api.model.SessionSummaryDto;

import java.util.List;
import java.util.Map;

/**
 * High-level conversation API consumed by REPL and external adapters.
 */
public interface ConversationService {

    SessionSnapshot startSession(String preferredSessionId, boolean fresh);

    SessionSnapshot resumeSession(String sessionId);

    List<SessionSummaryDto> listSessions(int limit);

    ConversationResponse sendUserTurn(String sessionId, String text);

    default ConversationResponse sendUserTurn(String sessionId, String text, Map<String, Object> attributes) {
        return sendUserTurn(sessionId, text);
    }

    ConversationResponse executeCommand(ConversationCommandRequest request);

    ConversationResponse approve(String sessionId);

    ConversationResponse deny(String sessionId);

    SessionSnapshot getSessionSnapshot(String sessionId);

    SessionRecoveryResponse recoverSession(String sessionId);

    CurrentConfigResponse getCurrentConfig(String sessionId);

    SaveConfigResponse saveCurrentConfig(String sessionId, String path);

    List<MemoryEntryDto> listMemory(String sessionId, int limit);

    List<ArtifactEntryDto> listArtifacts(String sessionId, int limit);

    ArtifactContentResponse getArtifact(String artifactId);
}
