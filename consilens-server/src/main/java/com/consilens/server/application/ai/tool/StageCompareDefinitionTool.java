package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentComparisonDraft;
import com.consilens.agent.api.state.AgentTaskDraftState;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.agent.core.security.CanonicalJsonDigester;
import com.consilens.server.application.ai.tool.dto.StageCompareDefinitionInput;
import com.consilens.server.application.ai.tool.dto.StageCompareDefinitionOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code stage_compare_definition}: writes the deterministic comparison draft
 * (keys/mappings/ignore) and the task draft from model-proposed candidates;
 * table and keys are mandatory, mappings are user-confirmed later.
 */
public final class StageCompareDefinitionTool
        implements AgentTool<StageCompareDefinitionInput, StageCompareDefinitionOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("stage_compare_definition")
                .description("固化比对任务草稿（表、键、字段映射、忽略列）")
                .riskLevel(ToolRiskLevel.LOW)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.CONTROL)
                .timeout(Duration.ofSeconds(10))
                .idempotent(false)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERY,
                        com.consilens.agent.api.state.AgentWorkflowStage.COLLECTING_DATASOURCES,
                        com.consilens.agent.api.state.AgentWorkflowStage.VALIDATING_CONNECTIONS,
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERING_METADATA,
                        com.consilens.agent.api.state.AgentWorkflowStage.DRAFTING_COMPARISON))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<StageCompareDefinitionInput> inputType() {
        return StageCompareDefinitionInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("taskName", MAPPER.createObjectNode().put("type", "string"));
        properties.set("description", MAPPER.createObjectNode().put("type", "string"));
        properties.set("sourceDraftId", MAPPER.createObjectNode().put("type", "string"));
        properties.set("targetDraftId", MAPPER.createObjectNode().put("type", "string"));
        properties.set("sourceDatabase", MAPPER.createObjectNode().put("type", "string"));
        properties.set("sourceTable", MAPPER.createObjectNode().put("type", "string"));
        properties.set("targetDatabase", MAPPER.createObjectNode().put("type", "string"));
        properties.set("targetTable", MAPPER.createObjectNode().put("type", "string"));
        properties.set("keys", MAPPER.createObjectNode().put("type", "array")
                .set("items", MAPPER.createObjectNode().put("type", "string")));
        properties.set("ignoreColumns", MAPPER.createObjectNode().put("type", "array")
                .set("items", MAPPER.createObjectNode().put("type", "string")));
        // 网关要求 array 必须带 items、object 必须带 properties/additionalProperties
        ObjectNode mappingProps = MAPPER.createObjectNode();
        mappingProps.set("source", MAPPER.createObjectNode().put("type", "string"));
        mappingProps.set("target", MAPPER.createObjectNode().put("type", "string"));
        mappingProps.set("sourceField", MAPPER.createObjectNode().put("type", "string"));
        mappingProps.set("targetField", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode mappingItem = MAPPER.createObjectNode();
        mappingItem.put("type", "object");
        mappingItem.set("properties", mappingProps);
        mappingItem.put("additionalProperties", true);
        ObjectNode mappingArray = MAPPER.createObjectNode();
        mappingArray.put("type", "array");
        mappingArray.set("items", mappingItem);
        properties.set("keyMappings", mappingArray);
        properties.set("fieldMappings", mappingArray);
        ObjectNode strategyHints = MAPPER.createObjectNode();
        strategyHints.put("type", "object");
        strategyHints.put("additionalProperties", true);
        properties.set("strategyHints", strategyHints);
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("taskName")
                .add("sourceDraftId").add("targetDraftId")
                .add("sourceTable").add("targetTable").add("keys"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<StageCompareDefinitionOutput> execute(StageCompareDefinitionInput input,
                                                                  AgentToolContext context) {
        if (input.getKeys() == null || input.getKeys().isEmpty()) {
            return AgentToolOutcome.error("CONFIG_VALIDATION_FAILED", false, "keys 不能为空");
        }
        if (input.getSourceTable() == null || input.getSourceTable().isBlank()
                || input.getTargetTable() == null || input.getTargetTable().isBlank()) {
            return AgentToolOutcome.error("CONFIG_VALIDATION_FAILED", false, "两侧表名必填");
        }
        ObjectNode digestInput = MAPPER.createObjectNode();
        digestInput.put("keys", MAPPER.valueToTree(input.getKeys()));
        digestInput.set("ignoreColumns", MAPPER.valueToTree(input.getIgnoreColumns()));
        digestInput.set("keyMappings", MAPPER.valueToTree(input.getKeyMappings()));
        digestInput.set("fieldMappings", MAPPER.valueToTree(input.getFieldMappings()));
        String configDigest = CanonicalJsonDigester.digest(digestInput);
        return AgentToolOutcome.ok(
                StageCompareDefinitionOutput.builder()
                        .taskName(input.getTaskName())
                        .sourceDatabase(input.getSourceDatabase())
                        .sourceTable(input.getSourceTable())
                        .targetDatabase(input.getTargetDatabase())
                        .targetTable(input.getTargetTable())
                        .keys(input.getKeys() == null ? List.of() : new ArrayList<>(input.getKeys()))
                        .keyMappings(input.getKeyMappings() == null ? List.of() : input.getKeyMappings())
                        .fieldMappings(input.getFieldMappings() == null ? List.of() : input.getFieldMappings())
                        .ignoreColumns(input.getIgnoreColumns() == null ? List.of() : input.getIgnoreColumns())
                        .strategyHints(input.getStrategyHints() == null ? Map.of() : input.getStrategyHints())
                        .configTemplateDigest(configDigest)
                        .build(),
                "比对草稿已固化: " + input.getTaskName());
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, StageCompareDefinitionOutput output) {
        com.consilens.agent.api.state.AgentDatasourceDraftState source = previous.getSource() == null
                ? com.consilens.agent.api.state.AgentDatasourceDraftState.builder()
                .draftId("draft_source").build()
                : previous.getSource();
        com.consilens.agent.api.state.AgentDatasourceDraftState target = previous.getTarget() == null
                ? com.consilens.agent.api.state.AgentDatasourceDraftState.builder()
                .draftId("draft_target").build()
                : previous.getTarget();
        return previous.toBuilder()
                .source(source.toBuilder()
                        .database(output.getSourceDatabase())
                        .table(output.getSourceTable())
                        .build())
                .target(target.toBuilder()
                        .database(output.getTargetDatabase())
                        .table(output.getTargetTable())
                        .build())
                .comparison(AgentComparisonDraft.builder()
                        .keys(output.getKeys())
                        .keyMappings(toFieldMappings(output.getKeyMappings(), true))
                        .fieldMappings(toFieldMappings(output.getFieldMappings(), false))
                        .ignoreColumns(output.getIgnoreColumns())
                        .strategyHints(output.getStrategyHints())
                        .configTemplateDigest(output.getConfigTemplateDigest())
                        .build())
                .task(AgentTaskDraftState.builder()
                        .name(output.getTaskName())
                        .description("")
                        .configTemplateDigest(output.getConfigTemplateDigest())
                        .build())
                .stage(AgentWorkflowStage.DRAFTING_COMPARISON)
                .build();
    }

    private static List<com.consilens.agent.api.state.AgentFieldMapping> toFieldMappings(
            List<Map<String, Object>> mappings, boolean keyMapping) {
        if (mappings == null) {
            return List.of();
        }
        List<com.consilens.agent.api.state.AgentFieldMapping> result = new ArrayList<>();
        for (Map<String, Object> mapping : mappings) {
            result.add(com.consilens.agent.api.state.AgentFieldMapping.builder()
                    .sourceField(String.valueOf(mapping.getOrDefault("source", mapping.get("sourceField"))))
                    .targetField(String.valueOf(mapping.getOrDefault("target", mapping.get("targetField"))))
                    .keyMapping(keyMapping)
                    .build());
        }
        return result;
    }
}
