package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * Lightweight artifact listing item exposed to clients.
 */
@Value
@Builder
public class ArtifactEntryDto {

    String artifactId;
    String sessionId;
    String type;
    String path;
    String sha256;
    Map<String, String> metadata;
    Instant createdAt;
}
