package com.consilens.agent.api.store;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Instant;

@Value
@Builder
@With
public class AgentApprovalRecord {
    String approvalId;
    String sessionId;
    String proposedRunId;
    AgentApprovalStatus status;
    String actionDigest;
    String safeSummary;
    String safeActionsJson;
    String actorId;
    Instant decidedAt;
    Instant expiresAt;
    long version;
}
