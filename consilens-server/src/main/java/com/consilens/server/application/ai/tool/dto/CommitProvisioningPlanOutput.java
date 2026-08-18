package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CommitProvisioningPlanOutput {
    String planId;
    String approvalId;
    String actionDigest;
    String summary;
}
