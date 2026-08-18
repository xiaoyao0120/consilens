package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.api.dto.DataSourceDto;
import com.consilens.server.application.ai.tool.dto.FindDatasourceInput;
import com.consilens.server.application.ai.tool.dto.FindDatasourceOutput;
import com.consilens.server.application.datasource.DataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Optional;

/**
 * {@code find_datasource}: exact-name dedup lookup. The result never contains
 * the password (DataSourceDto already strips it).
 */
public final class FindDatasourceTool implements AgentTool<FindDatasourceInput, FindDatasourceOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceService dataSourceService;

    public FindDatasourceTool(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("find_datasource")
                .description("按精确名称查询数据源（返回结果不含密码）")
                .riskLevel(ToolRiskLevel.LOW)
                .executionMode(ToolExecutionMode.PARALLEL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(10))
                .idempotent(true)
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
    public Class<FindDatasourceInput> inputType() {
        return FindDatasourceInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("name", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<FindDatasourceOutput> execute(FindDatasourceInput input,
                                                          AgentToolContext context) {
        Optional<DataSourceDto> found = dataSourceService.findByName(input.getName());
        if (found.isEmpty()) {
            return AgentToolOutcome.ok(
                    FindDatasourceOutput.builder().found(false).name(input.getName()).build(),
                    "未找到同名数据源");
        }
        DataSourceDto dto = found.get();
        return AgentToolOutcome.ok(
                FindDatasourceOutput.builder()
                        .found(true)
                        .id(dto.getId())
                        .name(dto.getName())
                        .type(dto.getType())
                        .paramWithoutSecret(dto.getParam())
                        .build(),
                "已找到数据源 " + dto.getName());
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, FindDatasourceOutput output) {
        return previous;
    }
}
