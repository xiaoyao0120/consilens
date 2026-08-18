package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentPlanStateRef;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.application.ai.plan.ProvisioningPlanCompiler;
import com.consilens.server.application.ai.tool.dto.PrepareProvisioningPlanInput;
import com.consilens.server.application.ai.tool.dto.PrepareProvisioningPlanOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * {@code prepare_provisioning_plan}: freezes the working state into a plan and
 * persists it (plan JSON never contains secrets or secret handles).
 */
public final class PrepareProvisioningPlanTool
        implements AgentTool<PrepareProvisioningPlanInput, PrepareProvisioningPlanOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProvisioningPlanCompiler compiler;
    private final AgentPersistence persistence;

    public PrepareProvisioningPlanTool(ProvisioningPlanCompiler compiler,
                                       AgentPersistence persistence) {
        this.compiler = compiler;
        this.persistence = persistence;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("prepare_provisioning_plan")
                .description("把当前草稿固化为待提交计划")
                .riskLevel(ToolRiskLevel.MEDIUM)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.CONTROL)
                .timeout(Duration.ofSeconds(10))
                .idempotent(false)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERY,
                        com.consilens.agent.api.state.AgentWorkflowStage.COLLECTING_DATASOURCES,
                        com.consilens.agent.api.state.AgentWorkflowStage.VALIDATING_CONNECTIONS,
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERING_METADATA,
                        com.consilens.agent.api.state.AgentWorkflowStage.DRAFTING_COMPARISON,
                        com.consilens.agent.api.state.AgentWorkflowStage.PLAN_READY))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<PrepareProvisioningPlanInput> inputType() {
        return PrepareProvisioningPlanInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", MAPPER.createObjectNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<PrepareProvisioningPlanOutput> execute(PrepareProvisioningPlanInput input,
                                                                   AgentToolContext context) {
        AgentProvisioningPlan plan;
        try {
            plan = compiler.compile(context.workingState());
        } catch (IllegalArgumentException e) {
            return AgentToolOutcome.error("CONFIG_VALIDATION_FAILED", false, e.getMessage());
        }
        AgentProvisioningPlan saved = plan.withSessionId(context.sessionId())
                .withStatus(com.consilens.agent.api.plan.AgentPlanStatus.PREPARED);
        boolean ok = persistence.saveReadyPlan(context.sessionId(), saved,
                persistence.findSession(context.sessionId()).orElseThrow().getVersion());
        if (!ok) {
            return AgentToolOutcome.error("CONCURRENT_MODIFICATION", false, "会话状态已变化，请重试");
        }
        return AgentToolOutcome.ok(
                PrepareProvisioningPlanOutput.builder()
                        .planId(plan.getPlanId())
                        .planDigestPrefix(plan.getPlanDigest().substring(0, 8))
                        .safeSummary(plan.getSafeSummary())
                        .build(),
                "计划已固化: " + plan.getSafeSummary());
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, PrepareProvisioningPlanOutput output) {
        AgentPlanStateRef ref = AgentPlanStateRef.builder()
                .planId(output.getPlanId())
                .planDigest(output.getPlanDigestPrefix())
                .status(com.consilens.agent.api.plan.AgentPlanStatus.PREPARED)
                .build();
        return previous.toBuilder()
                .provisioningPlan(ref)
                .stage(AgentWorkflowStage.PLAN_READY)
                .build();
    }
}
