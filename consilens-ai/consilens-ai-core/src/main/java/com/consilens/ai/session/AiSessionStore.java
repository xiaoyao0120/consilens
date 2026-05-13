package com.consilens.ai.session;

import com.consilens.ai.session.model.AiSession;

import java.util.List;
import java.util.Optional;

/**
 * Persistent store for AI sessions.
 */
public interface AiSessionStore {

    Optional<AiSession> load(String sessionId);

    default AiSession create() {
        return create(null);
    }

    AiSession create(String preferredSessionId);

    void save(AiSession session);

    List<AiSession> list();
}
