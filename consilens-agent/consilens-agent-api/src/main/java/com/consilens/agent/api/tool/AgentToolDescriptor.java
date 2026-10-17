package com.consilens.agent.api.tool;

import com.consilens.agent.api.state.AgentWorkflowStage;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.Set;

/**
 * Static server-side metadata for one tool. Empty {@code allowedStages} means
 * the tool is visible in every stage; preflight still gates by this set.
 */
@Value
@Builder
@With
public class AgentToolDescriptor {
    String name;
    String description;
    ToolRiskLevel riskLevel;
    ToolSideEffect sideEffect;
    String requiredPermission;
    boolean idempotent;
    @Builder.Default
    Set<AgentWorkflowStage> allowedStages = java.util.Set.of();
}
