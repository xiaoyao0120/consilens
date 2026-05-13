package com.consilens.ai.session;

import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Store for session-scoped AI artifacts.
 */
public interface AiArtifactStore {

    ArtifactRef write(String sessionId, ArtifactType type, byte[] content, Map<String, String> metadata);

    Optional<ArtifactRef> get(String artifactId);

    Optional<byte[]> read(String artifactId);

    Optional<ArtifactRef> latest(String sessionId, ArtifactType type);

    List<ArtifactRef> list(String sessionId);
}
