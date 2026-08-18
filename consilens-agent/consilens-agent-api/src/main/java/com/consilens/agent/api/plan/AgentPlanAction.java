package com.consilens.agent.api.plan;

import com.consilens.agent.api.store.AgentResourceType;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.Map;

/**
 * One frozen plan action. {@code safeArgs} never contains passwords, secret
 * handles or ciphertext; it references draftId/nonSecretParamDigest/
 * secretRequestId instead.
 */
@Value
@Builder
@With
public class AgentPlanAction {
    String actionId;
    int sequence;
    AgentPlanActionType actionType;
    String name;
    Map<String, Object> safeArgs;
    AgentPlanActionStatus status;
    String idempotencyKey;
    String inputDigest;
    AgentResourceType resourceType;
    String resourceId;
}
