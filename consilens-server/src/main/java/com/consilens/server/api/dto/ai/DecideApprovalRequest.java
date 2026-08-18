package com.consilens.server.api.dto.ai;

import com.consilens.agent.api.store.AgentApprovalDecision;
import lombok.Data;

@Data
public class DecideApprovalRequest {
    private String requestId;
    private AgentApprovalDecision decision;
    private String actionDigest;
    private Long version;
}
