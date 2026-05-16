package com.consilens.ai.conversation.api.model;

import lombok.Builder;
import lombok.Value;

/**
 * Recovery view for resuming a session after an interruption.
 */
@Value
@Builder
public class SessionRecoveryResponse {

    SessionSnapshot session;
    ArtifactContentResponse currentConfig;
    ArtifactContentResponse latestDiagnosis;
    ArtifactContentResponse latestAudit;
    String recommendedAction;
    String summary;
}
