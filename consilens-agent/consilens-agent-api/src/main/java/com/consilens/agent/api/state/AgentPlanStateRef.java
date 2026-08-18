package com.consilens.agent.api.state;

import com.consilens.agent.api.plan.AgentPlanStatus;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import lombok.Value;

@Value
@Builder
@Jacksonized
public class AgentPlanStateRef {
    String planId;
    String planDigest;
    AgentPlanStatus status;
}
