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
import com.consilens.server.application.ai.tool.dto.DatasourceRef;
import com.consilens.server.application.ai.tool.dto.ListDatasourcesInput;
import com.consilens.server.application.ai.tool.dto.ListDatasourcesOutput;
import com.consilens.server.application.datasource.DataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code list_datasources}: lists persisted datasources (id/name/type/default
 * database). Lets the agent discover what is already available before staging
 * new drafts; results never include the password.
 */
public final class ListDatasourcesTool
        implements AgentTool<ListDatasourcesInput, ListDatasourcesOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 50;

    private final DataSourceService dataSourceService;

    public ListDatasourcesTool(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("list_datasources")
                .description("列出系统已有数据源（id/名称/类型/默认库，不含密码），可按名称关键词过滤")
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
    public Class<ListDatasourcesInput> inputType() {
        return ListDatasourcesInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("keyword", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<ListDatasourcesOutput> execute(ListDatasourcesInput input,
                                                           AgentToolContext context) {
        String keyword = input.getKeyword() == null ? ""
                : input.getKeyword().trim().toLowerCase(Locale.ROOT);
        List<DatasourceRef> refs = new ArrayList<>();
        boolean truncated = false;
        for (DataSourceDto dto : dataSourceService.list()) {
            if (!keyword.isEmpty() && !dto.getName().toLowerCase(Locale.ROOT).contains(keyword)) {
                continue;
            }
            if (refs.size() >= MAX_RESULTS) {
                truncated = true;
                break;
            }
            Object database = dto.getParam() == null ? null : dto.getParam().get("database");
            refs.add(DatasourceRef.builder()
                    .id(dto.getId())
                    .name(dto.getName())
                    .type(dto.getType())
                    .database(database == null ? "" : String.valueOf(database))
                    .build());
        }
        return AgentToolOutcome.ok(
                ListDatasourcesOutput.builder()
                        .datasources(refs)
                        .truncated(truncated)
                        .build(),
                refs.isEmpty() ? "没有找到数据源" : "找到 " + refs.size() + " 个数据源");
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, ListDatasourcesOutput output) {
        return previous;
    }
}
