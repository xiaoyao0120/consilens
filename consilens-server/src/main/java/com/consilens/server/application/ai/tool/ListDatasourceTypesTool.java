package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.api.dto.DataSourceTypeDto;
import com.consilens.server.application.ai.tool.dto.DatasourceTypeItem;
import com.consilens.server.application.ai.tool.dto.ListDatasourceTypesInput;
import com.consilens.server.application.ai.tool.dto.ListDatasourceTypesOutput;
import com.consilens.server.application.datasource.DataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code list_datasource_types}: returns supported types and default ports.
 * driverClass is audit-only and never exposed to the model.
 */
public final class ListDatasourceTypesTool
        implements AgentTool<ListDatasourceTypesInput, ListDatasourceTypesOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceService dataSourceService;

    public ListDatasourceTypesTool(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("list_datasource_types")
                .description("列出系统支持的数据源类型与默认端口")
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
    public Class<ListDatasourceTypesInput> inputType() {
        return ListDatasourceTypesInput.class;
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
    public AgentToolOutcome<ListDatasourceTypesOutput> execute(ListDatasourceTypesInput input,
                                                               AgentToolContext context) {
        List<DataSourceTypeDto> types = dataSourceService.listTypes();
        if (types.isEmpty()) {
            return AgentToolOutcome.error("NO_DATASOURCE_TYPE_AVAILABLE", false,
                    "没有可用的数据源类型，请运行 server doctor 检查方言插件");
        }
        List<DatasourceTypeItem> items = types.stream()
                .map(t -> DatasourceTypeItem.builder()
                        .type(t.getType())
                        .defaultPort(t.getDefaultPort())
                        .build())
                .collect(Collectors.toList());
        return AgentToolOutcome.ok(
                ListDatasourceTypesOutput.builder().types(items).build(),
                "支持 " + items.size() + " 种数据源类型");
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, ListDatasourceTypesOutput output) {
        return previous;
    }
}
