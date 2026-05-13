package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Value;

/**
 * Reference to diff evidence produced by a deterministic execution.
 */
@Value
@Builder
public class EvidenceRef {

    String sessionId;
    String artifactId;
    String path;
}
