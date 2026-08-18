package com.consilens.agent.api.tool;

import com.consilens.agent.api.state.AgentWorkflowStage;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Duration;
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
    ToolExecutionMode executionMode;
    ToolSideEffect sideEffect;
    String requiredPermission;
    Duration timeout;
    boolean idempotent;
    @Builder.Default
    Set<AgentWorkflowStage> allowedStages = java.util.Set.of();
    ToolResultVisibility resultVisibility;

    public boolean isReadOnly() {
        return sideEffect == ToolSideEffect.READ;
    }
}
