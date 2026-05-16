package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * Artifact contents exposed through the conversation API.
 */
@Value
@Builder
public class ArtifactContentResponse {

    boolean found;
    String artifactId;
    String sessionId;
    String type;
    String path;
    String sha256;
    String contentType;
    String content;
    Map<String, String> metadata;
    Instant createdAt;
}
