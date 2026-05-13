package com.consilens.ai.execution.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

/**
 * Pointer to the most recent diff execution within a session.
 */
@Value
@Builder
@JsonDeserialize(builder = LatestDiffPointer.LatestDiffPointerBuilder.class)
public class LatestDiffPointer {

    String sessionId;
    String runId;
    String resultArtifactId;
    String evidenceArtifactId;

    @JsonPOJOBuilder(withPrefix = "")
    public static class LatestDiffPointerBuilder {
    }
}
