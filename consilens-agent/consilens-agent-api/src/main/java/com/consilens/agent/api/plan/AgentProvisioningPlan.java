package com.consilens.agent.api.plan;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Instant;
import java.util.List;

/**
 * Frozen provisioning plan approved as a whole. Approval binds to
 * {@code planDigest}; any parameter change invalidates the approval.
 */
@Value
@Builder
@With
public class AgentProvisioningPlan {
    String planId;
    String sessionId;
    String objectiveId;
    AgentPlanStatus status;
    String planDigest;
    String configTemplateDigest;
    String safeSummary;
    List<AgentPlanAction> actions;
    long version;
    Instant createdAt;
}
