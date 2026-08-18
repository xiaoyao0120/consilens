package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.agent.core.security.SensitiveValueGuard;
import com.consilens.connector.api.DataSourceField;
import com.consilens.server.application.ai.tool.dto.DatasourceFormField;
import com.consilens.server.application.ai.tool.dto.GetDatasourceFormSchemaInput;
import com.consilens.server.application.ai.tool.dto.GetDatasourceFormSchemaOutput;
import com.consilens.server.application.datasource.DataSourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code get_datasource_form_schema}: returns the required/non-secret form
 * fields for a dialect. Sensitive fields are marked so later draft tools can
 * route them to the out-of-band secret channel.
 */
public final class GetDatasourceFormSchemaTool
        implements AgentTool<GetDatasourceFormSchemaInput, GetDatasourceFormSchemaOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceService dataSourceService;

    public GetDatasourceFormSchemaTool(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("get_datasource_form_schema")
                .description("获取指定数据源类型的表单字段定义（含必填与敏感标记）")
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
    public Class<GetDatasourceFormSchemaInput> inputType() {
        return GetDatasourceFormSchemaInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("type", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("type"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<GetDatasourceFormSchemaOutput> execute(GetDatasourceFormSchemaInput input,
                                                                   AgentToolContext context) {
        List<DataSourceField> fields;
        try {
            fields = dataSourceService.getTypeConfig(input.getType());
        } catch (IllegalArgumentException e) {
            return AgentToolOutcome.error("DATASOURCE_PROBE_UNSUPPORTED", false,
                    "不支持的数据源类型: " + input.getType());
        }
        List<DatasourceFormField> mapped = fields.stream()
                .map(f -> DatasourceFormField.builder()
                        .name(f.getField())
                        .title(f.getTitle())
                        .controlType(f.getType())
                        .required(f.isRequired())
                        .defaultValue(f.getDefaultValue())
                        .options(f.getOptions())
                        .sensitive(f.isSensitive() || SensitiveValueGuard.isSensitiveName(f.getField()))
                        .build())
                .collect(Collectors.toList());
        return AgentToolOutcome.ok(
                GetDatasourceFormSchemaOutput.builder()
                        .type(input.getType())
                        .fields(mapped)
                        .build(),
                "已获取 " + input.getType() + " 表单定义");
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, GetDatasourceFormSchemaOutput output) {
        return previous;
    }
}
