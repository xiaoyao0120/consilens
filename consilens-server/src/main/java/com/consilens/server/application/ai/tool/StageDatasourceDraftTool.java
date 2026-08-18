package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentProbeStatus;
import com.consilens.agent.api.state.AgentSecretStatus;
import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.state.SlotSource;
import com.consilens.agent.api.store.AgentSecretStore;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.consilens.agent.core.security.SensitiveValueGuard;
import com.consilens.connector.api.DataSourceField;
import com.consilens.server.application.ai.tool.dto.AgentDraftSide;
import com.consilens.server.application.ai.tool.dto.StageDatasourceDraftInput;
import com.consilens.server.application.ai.tool.dto.StageDatasourceDraftOutput;
import com.consilens.server.application.datasource.DataSourceService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code stage_datasource_draft}: merges model-provided non-secret slots into
 * a draft, rejects unknown/sensitive fields, and when the non-secret schema is
 * complete creates the out-of-band secret request so the UI can collect
 * credentials without them ever entering the model context.
 */
public final class StageDatasourceDraftTool
        implements AgentTool<StageDatasourceDraftInput, StageDatasourceDraftOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceService dataSourceService;
    private final AgentSecretStore secretStore;
    private final ConsilensServerProperties properties;

    public StageDatasourceDraftTool(DataSourceService dataSourceService,
                                    AgentSecretStore secretStore,
                                    ConsilensServerProperties properties) {
        this.dataSourceService = dataSourceService;
        this.secretStore = secretStore;
        this.properties = properties;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("stage_datasource_draft")
                .description("合并非敏感槽位生成数据源草稿，返回缺失字段；就绪后创建安全凭据请求")
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
                        com.consilens.agent.api.state.AgentWorkflowStage.DRAFTING_COMPARISON,
                        com.consilens.agent.api.state.AgentWorkflowStage.PLAN_READY))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<StageDatasourceDraftInput> inputType() {
        return StageDatasourceDraftInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        // 注意：ObjectNode.put(String, JsonNode) 已废弃且返回 null，必须用 set
        ObjectNode side = MAPPER.createObjectNode();
        side.put("type", "string");
        side.set("enum", MAPPER.createArrayNode().add("SOURCE").add("TARGET"));
        properties.set("side", side);
        properties.set("name", MAPPER.createObjectNode().put("type", "string"));
        properties.set("type", MAPPER.createObjectNode().put("type", "string"));
        properties.set("datasourceId", MAPPER.createObjectNode().put("type", "string"));
        // 网关要求 object 类型必须显式声明 additionalProperties/properties
        properties.set("nonSecretParams", MAPPER.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", true));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("side").add("name").add("type"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<StageDatasourceDraftOutput> execute(StageDatasourceDraftInput input,
                                                                AgentToolContext context) {
        if (input.getSide() == null) {
            return AgentToolOutcome.error("TOOL_ARGUMENT_INVALID", false, "side is required");
        }
        // 复用已有数据源：跳过草稿/凭据流程，直接引用（编译时走 REUSE_DATASOURCE）
        if (input.getDatasourceId() != null && !input.getDatasourceId().isBlank()) {
            com.consilens.server.api.dto.DataSourceDto dto =
                    dataSourceService.get(Long.valueOf(input.getDatasourceId()));
            if (dto == null) {
                return AgentToolOutcome.error("DATASOURCE_NOT_FOUND", false,
                        "数据源不存在: " + input.getDatasourceId());
            }
            Object database = dto.getParam() == null ? null : dto.getParam().get("database");
            StageDatasourceDraftOutput output = StageDatasourceDraftOutput.builder()
                    .draftId(input.getSide().draftId())
                    .datasourceId(input.getDatasourceId())
                    .name(input.getName() == null || input.getName().isBlank()
                            ? dto.getName() : input.getName())
                    .type(dto.getType())
                    .missingNonSecretFields(List.of())
                    .requiredSecretFields(List.of())
                    .readyForSecret(false)
                    .mergedNonSecretParams(Map.of())
                    .build();
            return AgentToolOutcome.ok(output, "已复用已有数据源 " + dto.getName()
                    + (database == null ? "" : "（库 " + database + "）"));
        }
        List<DataSourceField> fields;
        try {
            fields = dataSourceService.getTypeConfig(input.getType());
        } catch (IllegalArgumentException e) {
            return AgentToolOutcome.error("DATASOURCE_PROBE_UNSUPPORTED", false,
                    "不支持的数据源类型: " + input.getType());
        }

        Set<String> allowed = new HashSet<>();
        for (DataSourceField field : fields) {
            allowed.add(field.getField());
        }
        Map<String, Object> provided = input.getNonSecretParams() == null
                ? Map.of() : input.getNonSecretParams();
        for (String key : provided.keySet()) {
            if (!allowed.contains(key)) {
                return AgentToolOutcome.error("TOOL_ARGUMENT_INVALID", false,
                        "未知字段: " + key);
            }
            if (isSensitiveField(fields, key)) {
                return AgentToolOutcome.error("SECRET_IN_MODEL_ARGUMENTS", false,
                        "敏感字段必须通过安全表单提交: " + key);
            }
        }

        List<String> missingNonSecret = new ArrayList<>();
        List<String> requiredSecrets = new ArrayList<>();
        for (DataSourceField field : fields) {
            if (isSensitiveField(fields, field.getField())) {
                // 敏感字段一律走安全表单（即使方言模板标为 optional），
                // 密码等凭据绝不进入模型参数，也不作为“缺失非敏感字段”询问。
                requiredSecrets.add(field.getField());
                continue;
            }
            if (!field.isRequired()) {
                continue;
            }
            if (!provided.containsKey(field.getField())
                    || String.valueOf(provided.get(field.getField())).isBlank()) {
                missingNonSecret.add(field.getField());
            }
        }

        boolean readyForSecret = missingNonSecret.isEmpty();
        String draftId = input.getSide().draftId();
        String secretRequestId = null;
        Instant expiresAt = null;
        if (readyForSecret) {
            ConsilensServerProperties.Ai ai = properties.getAi();
            // 复用仍有效（REQUESTED）的旧请求，避免重复就绪产生孤儿 PENDING。
            AgentDatasourceDraftState existing = input.getSide() == AgentDraftSide.SOURCE
                    ? context.workingState().getSource() : context.workingState().getTarget();
            if (existing != null && existing.getSecretStatus() == AgentSecretStatus.REQUESTED
                    && existing.getSecretRequestId() != null) {
                secretRequestId = existing.getSecretRequestId();
                // 复用仅当请求仍有效；过期则换新请求。
                expiresAt = secretStore.expiresAt(secretRequestId, context.actorId(),
                        context.sessionId()).orElse(null);
                if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
                    secretRequestId = null;
                }
            }
            if (secretRequestId == null) {
                expiresAt = Instant.now().plusSeconds(ai.getSecretTtlSeconds());
                secretRequestId = secretStore.createRequest(context.actorId(), context.sessionId(),
                        draftId, expiresAt, ai.getSecretMaxReads());
            } else {
                // 复用的有效请求保留其原始过期时间。
            }
        }

        StageDatasourceDraftOutput output = StageDatasourceDraftOutput.builder()
                .draftId(draftId)
                .name(input.getName())
                .type(input.getType())
                .missingNonSecretFields(missingNonSecret)
                .requiredSecretFields(requiredSecrets)
                .readyForSecret(readyForSecret)
                .mergedNonSecretParams(provided)
                .secretRequestId(secretRequestId)
                .build();
        if (!readyForSecret) {
            return AgentToolOutcome.ok(output, "草稿已保存，还缺: " + missingNonSecret);
        }
        return AgentToolOutcome.<StageDatasourceDraftOutput>builder()
                .success(true)
                .structuredData(output)
                .content("草稿就绪，请在安全表单填写: " + requiredSecrets)
                .visibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .secretInputRequired(true)
                .secretRequestId(secretRequestId)
                .draftId(draftId)
                .missingSlots(requiredSecrets)
                .secretExpiresAt(expiresAt)
                .build();
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, StageDatasourceDraftOutput output) {
        AgentDraftSide side = output.getDraftId().equals(AgentDraftSide.SOURCE.draftId())
                ? AgentDraftSide.SOURCE : AgentDraftSide.TARGET;
        Map<String, AgentSlotValue> slots = new LinkedHashMap<>();
        if (output.getMergedNonSecretParams() != null) {
            for (Map.Entry<String, Object> entry : output.getMergedNonSecretParams().entrySet()) {
                slots.put(entry.getKey(), AgentSlotValue.of(entry.getValue(), SlotSource.USER_INFERRED));
            }
        }
        AgentDatasourceDraftState existing = side == AgentDraftSide.SOURCE
                ? previous.getSource() : previous.getTarget();
        AgentDatasourceDraftState base = existing == null
                ? AgentDatasourceDraftState.builder().draftId(output.getDraftId()).build()
                : existing;
        AgentDatasourceDraftState updated = base.toBuilder()
                .name(output.getName())
                .type(output.getType())
                .datasourceId(output.getDatasourceId())
                .nonSecretParams(slots)
                .secretStatus(output.isReadyForSecret()
                        ? AgentSecretStatus.REQUESTED : AgentSecretStatus.NONE)
                .secretRequestId(output.getSecretRequestId())
                // 复用已有数据源时连接信息由系统管理，视为已验证，编译走 REUSE
                .probeStatus(output.getDatasourceId() != null
                        ? AgentProbeStatus.SUCCEEDED : AgentProbeStatus.NOT_PROBED)
                .build();
        return previous.toBuilder()
                .source(side == AgentDraftSide.SOURCE ? updated : previous.getSource())
                .target(side == AgentDraftSide.TARGET ? updated : previous.getTarget())
                .stage(previous.getStage().ordinal()
                        > AgentWorkflowStage.COLLECTING_DATASOURCES.ordinal()
                        ? previous.getStage() : AgentWorkflowStage.COLLECTING_DATASOURCES)
                .build();
    }

    private static boolean isSensitiveField(List<DataSourceField> fields, String key) {
        for (DataSourceField field : fields) {
            if (field.getField().equals(key)) {
                return field.isSensitive() || SensitiveValueGuard.isSensitiveName(key);
            }
        }
        return SensitiveValueGuard.isSensitiveName(key);
    }
}
