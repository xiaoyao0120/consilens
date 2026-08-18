package com.consilens.agent.api.policy;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentPolicyResult {
    AgentPolicyDecision decision;
    String errorCode;
    String reason;
    String requiredPermission;

    public static AgentPolicyResult allow() {
        return AgentPolicyResult.builder().decision(AgentPolicyDecision.ALLOW).build();
    }

    public static AgentPolicyResult block(String errorCode, String reason) {
        return AgentPolicyResult.builder().decision(AgentPolicyDecision.BLOCK)
                .errorCode(errorCode).reason(reason).build();
    }

    public static AgentPolicyResult requireApproval(String summary) {
        return AgentPolicyResult.builder().decision(AgentPolicyDecision.REQUIRE_APPROVAL)
                .reason(summary).build();
    }
}
