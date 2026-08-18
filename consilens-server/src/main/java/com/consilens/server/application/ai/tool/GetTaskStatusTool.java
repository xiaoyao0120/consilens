package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.api.dto.TaskQueryResponse;
import com.consilens.server.application.ai.tool.dto.GetTaskStatusInput;
import com.consilens.server.application.ai.tool.dto.GetTaskStatusOutput;
import com.consilens.server.application.task.RunTaskQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * {@code get_task_status}: observes a submitted task with bounded results.
 */
public final class GetTaskStatusTool implements AgentTool<GetTaskStatusInput, GetTaskStatusOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunTaskQueryService queryService;

    public GetTaskStatusTool(RunTaskQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("get_task_status")
                .description("查询任务运行状态与产物")
                .riskLevel(ToolRiskLevel.LOW)
                .executionMode(ToolExecutionMode.PARALLEL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(10))
                .idempotent(true)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.RESOURCES_READY,
                        com.consilens.agent.api.state.AgentWorkflowStage.COMPLETED))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<GetTaskStatusInput> inputType() {
        return GetTaskStatusInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("taskId", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("taskId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<GetTaskStatusOutput> execute(GetTaskStatusInput input,
                                                         AgentToolContext context) {
        TaskQueryResponse response = queryService.getTask(input.getTaskId(), "agent-" + context.runId());
        return AgentToolOutcome.ok(
                GetTaskStatusOutput.builder()
                        .taskId(response.getTaskId())
                        .status(response.getStatus())
                        .definitionName(response.getDefinitionName())
                        .artifacts(response.getArtifacts() == null ? java.util.List.of()
                                : response.getArtifacts().stream()
                                .map(a -> a.getArtifactId() == null ? "" : a.getArtifactId())
                                .collect(Collectors.toList()))
                        .build(),
                "任务状态: " + response.getStatus());
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, GetTaskStatusOutput output) {
        return previous;
    }
}
