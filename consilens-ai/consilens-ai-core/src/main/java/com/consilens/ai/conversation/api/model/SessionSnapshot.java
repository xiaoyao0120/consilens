package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Snapshot of session state exposed to clients.
 */
@Value
@Builder
public class SessionSnapshot {

    String sessionId;
    String summary;
    String currentObjective;
    String status;
    String currentTask;
    String currentConfigArtifactId;
    String latestRunArtifactId;
    String latestDiagnosisArtifactId;
    String latestAuditArtifactId;
    PendingQuestionDto pendingQuestion;
    PendingApprovalDto pendingApproval;
    Instant createdAt;
    Instant updatedAt;
}
