package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.ai.tool.dto.ColumnInfo;
import com.consilens.server.application.ai.tool.dto.GetTableSchemaInput;
import com.consilens.server.application.ai.tool.dto.GetTableSchemaOutput;
import com.consilens.server.application.datasource.DataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code get_table_schema}: returns column structure (name/type/nullable)
 * plus primary keys for a table in a persisted datasource. Lets the agent
 * confirm table shape and pick comparison keys without staging a draft.
 */
public final class GetTableSchemaTool
        implements AgentTool<GetTableSchemaInput, GetTableSchemaOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceService dataSourceService;

    public GetTableSchemaTool(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("get_table_schema")
                .description("查询已有数据源中指定表的表结构（列名/类型/可空/主键）")
                .riskLevel(ToolRiskLevel.LOW)
                .executionMode(ToolExecutionMode.PARALLEL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(20))
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
    public Class<GetTableSchemaInput> inputType() {
        return GetTableSchemaInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("datasourceId", MAPPER.createObjectNode().put("type", "string"));
        properties.set("database", MAPPER.createObjectNode().put("type", "string"));
        properties.set("table", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode()
                .add("datasourceId").add("database").add("table"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<GetTableSchemaOutput> execute(GetTableSchemaInput input,
                                                          AgentToolContext context) {
        if (input.getDatasourceId() == null || input.getDatabase() == null
                || input.getTable() == null) {
            return AgentToolOutcome.error("TOOL_ARGUMENT_INVALID", false,
                    "datasourceId/database/table 必填");
        }
        Long datasourceId = Long.valueOf(input.getDatasourceId());
        List<MetadataColumnDto> columns = dataSourceService.getColumns(
                datasourceId, input.getDatabase(), input.getTable());
        List<String> primaryKeys;
        try {
            primaryKeys = dataSourceService.getPrimaryKeys(
                    datasourceId, input.getDatabase(), input.getTable());
        } catch (Exception e) {
            primaryKeys = List.of();
        }
        Set<String> keySet = new HashSet<>(primaryKeys);
        List<ColumnInfo> columnInfos = new ArrayList<>();
        for (MetadataColumnDto column : columns) {
            columnInfos.add(ColumnInfo.builder()
                    .name(column.getName())
                    .dataType(column.getDataType())
                    .nullable(Boolean.TRUE.equals(column.getNullable()))
                    .primaryKey(keySet.contains(column.getName()))
                    .build());
        }
        return AgentToolOutcome.ok(
                GetTableSchemaOutput.builder()
                        .datasourceId(input.getDatasourceId())
                        .database(input.getDatabase())
                        .table(input.getTable())
                        .columns(columnInfos)
                        .primaryKeys(primaryKeys)
                        .build(),
                "表结构已读取，共 " + columnInfos.size() + " 列");
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, GetTableSchemaOutput output) {
        return previous;
    }
}
