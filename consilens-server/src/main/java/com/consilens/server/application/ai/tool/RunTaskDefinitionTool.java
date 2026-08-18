package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.server.application.ai.approval.AgentApprovalService;
import com.consilens.server.application.taskdefinition.TaskDefinitionService;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.application.ai.tool.dto.RunTaskDefinitionInput;
import com.consilens.server.application.ai.tool.dto.RunTaskDefinitionOutput;
import com.consilens.server.application.taskdefinition.TaskDefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;

/**
 * {@code run_task_definition}: submits one run of a task definition and
 * returns the accepted task id. Run-level separate approval is wired in the
 * eval/UI phase (WP-15); the write itself is idempotent via serialNo.
 */
public final class RunTaskDefinitionTool
        implements AgentTool<RunTaskDefinitionInput, RunTaskDefinitionOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskDefinitionService taskDefinitionService;
    private final AgentApprovalService approvalService;

    public RunTaskDefinitionTool(TaskDefinitionService taskDefinitionService,
                                 AgentApprovalService approvalService) {
        this.taskDefinitionService = taskDefinitionService;
        this.approvalService = approvalService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("run_task_definition")
                .description("提交一次任务定义运行（需单独审批），返回任务 ID")
                .riskLevel(ToolRiskLevel.HIGH)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.WRITE)
                .timeout(Duration.ofSeconds(30))
                .idempotent(true)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.RESOURCES_READY,
                        com.consilens.agent.api.state.AgentWorkflowStage.COMPLETED))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<RunTaskDefinitionInput> inputType() {
        return RunTaskDefinitionInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("definitionId", MAPPER.createObjectNode().put("type", "string"));
        properties.set("serialNo", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("definitionId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<RunTaskDefinitionOutput> execute(RunTaskDefinitionInput input,
                                                             AgentToolContext context) {
        // 白名单式归属：任务必须由本会话的计划创建，definitionId 必须匹配。
        String ownedDefinitionId = context.workingState().getTask() == null
                ? null : context.workingState().getTask().getDefinitionId();
        if (ownedDefinitionId == null
                || !ownedDefinitionId.equals(input.getDefinitionId())) {
            return AgentToolOutcome.error("TOOL_PERMISSION_DENIED", false,
                    "definitionId 必须是当前会话已创建的任务");
        }
        try {
            Long.parseLong(input.getDefinitionId());
        } catch (NumberFormatException e) {
            return AgentToolOutcome.error("TOOL_ARGUMENT_INVALID", false, "definitionId 必须是数字");
        }
        AgentApprovalRecord approval = approvalService.createApproval(
                context.sessionId(), "run:" + input.getDefinitionId(),
                approvalService.computeActionDigest("run_task_definition",
                        MAPPER.valueToTree(input), context.workingState().getResourceVersions(), "v1"),
                "运行任务 " + input.getDefinitionId(),
                MAPPER.createObjectNode().put("definitionId", input.getDefinitionId()).toString(),
                Instant.now().plusSeconds(600));
        return AgentToolOutcome.<RunTaskDefinitionOutput>builder()
                .success(true)
                .structuredData(RunTaskDefinitionOutput.builder()
                        .taskId("pending-" + approval.getApprovalId())
                        .status("WAITING_APPROVAL")
                        .build())
                .content("任务运行需要单独审批")
                .visibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .approvalRequired(true)
                .approvalId(approval.getApprovalId())
                .actionDigest(approval.getActionDigest())
                .approvalSummary("运行任务 " + input.getDefinitionId())
                .build();
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, RunTaskDefinitionOutput output) {
        return previous;
    }
}
