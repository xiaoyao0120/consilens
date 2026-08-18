package com.consilens.server.application.ai.tool.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PrepareProvisioningPlanOutput {
    String planId;
    String planDigestPrefix;
    String safeSummary;
}
