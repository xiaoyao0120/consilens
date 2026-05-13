package com.consilens.ai.session.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

/**
 * Reference to a persisted session artifact.
 */
@Value
@Builder
@JsonDeserialize(builder = ArtifactRef.ArtifactRefBuilder.class)
public class ArtifactRef {

    String artifactId;
    String sessionId;
    ArtifactType type;
    String path;
    String sha256;
    Map<String, String> metadata;
    Instant createdAt;

    @JsonPOJOBuilder(withPrefix = "")
    public static class ArtifactRefBuilder {
    }
}
