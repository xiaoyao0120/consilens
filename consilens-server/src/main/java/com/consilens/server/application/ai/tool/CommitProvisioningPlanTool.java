package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.plan.AgentPlanStatus;
import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.application.ai.approval.AgentApprovalService;
import com.consilens.server.application.ai.plan.ProvisioningPlanCompiler;
import com.consilens.server.application.ai.tool.dto.CommitProvisioningPlanInput;
import com.consilens.server.application.ai.tool.dto.CommitProvisioningPlanOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code commit_provisioning_plan}: creates the structured approval bound to
 * the frozen plan digest; the write itself never happens here.
 */
public final class CommitProvisioningPlanTool
        implements AgentTool<CommitProvisioningPlanInput, CommitProvisioningPlanOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentPersistence persistence;
    private final AgentApprovalService approvalService;
    private final ProvisioningPlanCompiler planCompiler;

    public CommitProvisioningPlanTool(AgentPersistence persistence,
                                      AgentApprovalService approvalService,
                                      ProvisioningPlanCompiler planCompiler) {
        this.persistence = persistence;
        this.approvalService = approvalService;
        this.planCompiler = planCompiler;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("commit_provisioning_plan")
                .description("提交计划进入结构化审批（批准后由系统执行）")
                .riskLevel(ToolRiskLevel.HIGH)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.WRITE)
                .timeout(Duration.ofSeconds(10))
                .idempotent(false)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERY,
                        com.consilens.agent.api.state.AgentWorkflowStage.COLLECTING_DATASOURCES,
                        com.consilens.agent.api.state.AgentWorkflowStage.VALIDATING_CONNECTIONS,
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERING_METADATA,
                        com.consilens.agent.api.state.AgentWorkflowStage.DRAFTING_COMPARISON,
                        com.consilens.agent.api.state.AgentWorkflowStage.PLAN_READY,
                        com.consilens.agent.api.state.AgentWorkflowStage.AWAITING_COMMIT_APPROVAL))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<CommitProvisioningPlanInput> inputType() {
        return CommitProvisioningPlanInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("planId", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<CommitProvisioningPlanOutput> execute(CommitProvisioningPlanInput input,
                                                                  AgentToolContext context) {
        String planId = input.getPlanId();
        if (planId == null || planId.isBlank()) {
            planId = context.workingState().getProvisioningPlan() == null
                    ? null : context.workingState().getProvisioningPlan().getPlanId();
        }
        AgentProvisioningPlan plan = planId == null ? null : persistence.findPlan(planId).orElse(null);
        if (plan == null) {
            return AgentToolOutcome.error("PLAN_NOT_READY", false, "计划不存在");
        }
        if (plan.getStatus() != AgentPlanStatus.PREPARED) {
            return AgentToolOutcome.error("PLAN_DIGEST_MISMATCH", false, "计划状态已变化");
        }
        // 按设计 24.5：以当前 working state 交叉编译，任一参数变化都会使审批失效。
        String crossCompiledDigest;
        try {
            crossCompiledDigest = planCompiler.compile(context.workingState()).getPlanDigest();
        } catch (IllegalArgumentException e) {
            return AgentToolOutcome.error("CONFIG_VALIDATION_FAILED", false,
                    "计划草稿已不再满足编译条件: " + e.getMessage());
        }
        if (!crossCompiledDigest.equals(plan.getPlanDigest())) {
            return AgentToolOutcome.error("PLAN_DIGEST_MISMATCH", false,
                    "计划参数已变化，请重新 prepare");
        }
        String actionsJson;
        try {
            actionsJson = MAPPER.writeValueAsString(plan.getActions());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return AgentToolOutcome.error("INTERNAL_ERROR", true, "无法序列化计划动作");
        }
        AgentApprovalRecord approval = approvalService.createApproval(
                context.sessionId(), plan.getPlanId(), plan.getPlanDigest(),
                plan.getSafeSummary(), actionsJson, Instant.now().plusSeconds(600));
        return AgentToolOutcome.<CommitProvisioningPlanOutput>builder()
                .success(true)
                .structuredData(CommitProvisioningPlanOutput.builder()
                        .planId(plan.getPlanId())
                        .approvalId(approval.getApprovalId())
                        .actionDigest(approval.getActionDigest())
                        .summary(plan.getSafeSummary())
                        .build())
                .content("已生成审批: " + plan.getSafeSummary())
                .visibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .approvalRequired(true)
                .approvalId(approval.getApprovalId())
                .actionDigest(approval.getActionDigest())
                .approvalSummary(plan.getSafeSummary())
                .build();
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, CommitProvisioningPlanOutput output) {
        return previous.toBuilder()
                .stage(AgentWorkflowStage.AWAITING_COMMIT_APPROVAL)
                .build();
    }
}
