package com.consilens.ai.session.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Session metadata for the refactored AI runtime.
 */
@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = AiSession.AiSessionBuilder.class)
public class AiSession {

    String sessionId;
    Instant createdAt;
    Instant updatedAt;
    String title;
    String summary;
    String currentObjective;
    String status;
    String currentTask;
    String currentConfigArtifactId;
    String latestRunArtifactId;
    String latestDiagnosisArtifactId;
    String latestAuditArtifactId;
    String latestApprovalId;
    PendingQuestionState pendingQuestion;
    PendingApprovalState pendingApproval;

    @JsonPOJOBuilder(withPrefix = "")
    public static class AiSessionBuilder {
    }
}
