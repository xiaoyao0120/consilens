package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentProbeStatus;
import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentSecretStore;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.server.api.dto.MetadataColumnDto;
import com.consilens.server.application.ai.metadata.TransientConnectionSpec;
import com.consilens.server.application.ai.metadata.TransientDatasourceMetadataService;
import com.consilens.server.application.ai.tool.dto.InspectDraftMetadataInput;
import com.consilens.server.application.ai.tool.dto.InspectDraftMetadataOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code inspect_draft_metadata}: lists databases/tables/columns from a draft
 * without persisting the datasource. Column lists are capped at 200 with a
 * truncated flag (design 21.2).
 */
public final class InspectDraftMetadataTool
        implements AgentTool<InspectDraftMetadataInput, InspectDraftMetadataOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_COLUMNS = 200;

    private final TransientDatasourceMetadataService metadataService;
    private final AgentSecretStore secretStore;

    public InspectDraftMetadataTool(TransientDatasourceMetadataService metadataService,
                                    AgentSecretStore secretStore) {
        this.metadataService = metadataService;
        this.secretStore = secretStore;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("inspect_draft_metadata")
                .description("读取草稿连接下的库、表、列元数据（不落库）")
                .riskLevel(ToolRiskLevel.MEDIUM)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(30))
                .idempotent(false)
                .allowedStages(java.util.Set.of(
                        AgentWorkflowStage.COLLECTING_DATASOURCES,
                        AgentWorkflowStage.VALIDATING_CONNECTIONS,
                        AgentWorkflowStage.DISCOVERING_METADATA))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<InspectDraftMetadataInput> inputType() {
        return InspectDraftMetadataInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("draftId", MAPPER.createObjectNode().put("type", "string"));
        // 注意：ObjectNode.put(String, JsonNode) 已废弃且返回 null，必须用 set
        ObjectNode operation = MAPPER.createObjectNode();
        operation.put("type", "string");
        operation.set("enum", MAPPER.createArrayNode()
                .add("DATABASES").add("TABLES").add("COLUMNS"));
        properties.set("operation", operation);
        properties.set("database", MAPPER.createObjectNode().put("type", "string"));
        properties.set("table", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("draftId").add("operation"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<InspectDraftMetadataOutput> execute(InspectDraftMetadataInput input,
                                                                AgentToolContext context) {
        AgentDatasourceDraftState draft = findDraft(context.workingState(), input.getDraftId());
        if (draft == null || draft.getSecretRequestId() == null) {
            return AgentToolOutcome.error("DATASOURCE_NOT_FOUND", false,
                    "草稿不存在或未收集凭据: " + input.getDraftId());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (draft.getNonSecretParams() != null) {
            for (Map.Entry<String, AgentSlotValue> entry : draft.getNonSecretParams().entrySet()) {
                params.put(entry.getKey(), entry.getValue().getValue());
            }
        }
        try {
            char[] secret = secretStore.resolve(draft.getSecretRequestId(), context.actorId(),
                    context.sessionId(), "metadata", Instant.now())
                    .orElseThrow(() -> new IllegalStateException("secret not resolvable"));
            TransientConnectionSpec spec = TransientConnectionSpec.builder()
                    .type(draft.getType())
                    .host(String.valueOf(params.get("host")))
                    .port(integerValue(params.get("port")))
                    .database(String.valueOf(params.get("database")))
                    .username(String.valueOf(params.get("username")))
                    .password(SecretPayloadCodec.extract(secret, "password").toCharArray())
                    .options(optionsOf(params))
                    .build();
            switch (input.getOperation() == null ? "" : input.getOperation()) {
                case "DATABASES":
                    return AgentToolOutcome.ok(
                            InspectDraftMetadataOutput.builder()
                                    .draftId(input.getDraftId()).operation("DATABASES")
                                    .databases(metadataService.listDatabases(spec))
                                    .build(),
                            "库列表已读取");
                case "TABLES":
                    return AgentToolOutcome.ok(
                            InspectDraftMetadataOutput.builder()
                                    .draftId(input.getDraftId()).operation("TABLES")
                                    .database(input.getDatabase())
                                    .tables(metadataService.listTables(spec, input.getDatabase()))
                                    .build(),
                            "表列表已读取");
                case "COLUMNS":
                    List<MetadataColumnDto> all = metadataService.listColumns(spec,
                            input.getDatabase(), input.getTable());
                    boolean truncated = all.size() > MAX_COLUMNS;
                    return AgentToolOutcome.ok(
                            InspectDraftMetadataOutput.builder()
                                    .draftId(input.getDraftId()).operation("COLUMNS")
                                    .database(input.getDatabase()).table(input.getTable())
                                    .columns(truncated ? new ArrayList<>(all.subList(0, MAX_COLUMNS)) : all)
                                    .truncated(truncated)
                                    .build(),
                            "列列表已读取");
                default:
                    return AgentToolOutcome.error("TOOL_ARGUMENT_INVALID", false,
                            "operation 必须为 DATABASES/TABLES/COLUMNS");
            }
        } catch (Exception e) {
            return AgentToolOutcome.error("METADATA_READ_FAILED", true,
                    com.consilens.server.application.connection.JdbcConnectionSupport.safeMessage(e));
        }
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, InspectDraftMetadataOutput output) {
        com.consilens.server.application.ai.tool.dto.AgentDraftSide side =
                output.getDraftId().equals("draft_source")
                        ? com.consilens.server.application.ai.tool.dto.AgentDraftSide.SOURCE
                        : com.consilens.server.application.ai.tool.dto.AgentDraftSide.TARGET;
        AgentDatasourceDraftState base = side == com.consilens.server.application.ai.tool.dto.AgentDraftSide.SOURCE
                ? previous.getSource() : previous.getTarget();
        if (base == null) {
            return previous;
        }
        AgentDatasourceDraftState updated = base.toBuilder()
                .database(output.getDatabase() == null ? base.getDatabase() : output.getDatabase())
                .table(output.getTable() == null ? base.getTable() : output.getTable())
                .probeStatus(AgentProbeStatus.SUCCEEDED)
                .build();
        return previous.toBuilder()
                .source(side == com.consilens.server.application.ai.tool.dto.AgentDraftSide.SOURCE
                        ? updated : previous.getSource())
                .target(side == com.consilens.server.application.ai.tool.dto.AgentDraftSide.TARGET
                        ? updated : previous.getTarget())
                .build();
    }

    private static AgentDatasourceDraftState findDraft(AgentWorkingState state, String draftId) {
        if (state.getSource() != null && draftId.equals(state.getSource().getDraftId())) {
            return state.getSource();
        }
        if (state.getTarget() != null && draftId.equals(state.getTarget().getDraftId())) {
            return state.getTarget();
        }
        return null;
    }

    private static Map<String, Object> optionsOf(Map<String, Object> params) {
        Map<String, Object> options = new LinkedHashMap<>();
        for (String key : new String[]{"sid", "schema", "properties"}) {
            if (params.containsKey(key)) {
                options.put(key, params.get(key));
            }
        }
        return options;
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
