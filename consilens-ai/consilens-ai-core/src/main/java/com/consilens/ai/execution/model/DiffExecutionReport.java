package com.consilens.ai.execution.model;

import lombok.Builder;
import lombok.Value;

/**
 * Report for a deterministic diff execution.
 */
@Value
@Builder
public class DiffExecutionReport {

    String sessionId;
    String runId;
    boolean success;
    String summary;
    String summaryArtifactId;
    String resultArtifactId;
    EvidenceRef evidenceRef;
}
