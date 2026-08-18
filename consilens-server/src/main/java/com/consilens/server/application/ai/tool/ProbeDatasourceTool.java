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
import com.consilens.agent.core.security.CanonicalJsonDigester;
import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.application.ai.tool.dto.AgentDraftSide;
import com.consilens.server.application.ai.tool.dto.ProbeDatasourceInput;
import com.consilens.server.application.ai.tool.dto.ProbeDatasourceOutput;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code probe_datasource}: resolves the out-of-band secret for a draft and
 * runs the existing connection test without creating the datasource. The
 * secret never enters tool arguments or the model context.
 */
public final class ProbeDatasourceTool implements AgentTool<ProbeDatasourceInput, ProbeDatasourceOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentSecretStore secretStore;
    private final ConnectionTestService connectionTestService;
    private final ConsilensServerProperties properties;

    public ProbeDatasourceTool(AgentSecretStore secretStore,
                               ConnectionTestService connectionTestService,
                               ConsilensServerProperties properties) {
        this.secretStore = secretStore;
        this.connectionTestService = connectionTestService;
        this.properties = properties;
    }

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("probe_datasource")
                .description("使用已提交的凭据测试草稿连接（不创建数据源）")
                .riskLevel(ToolRiskLevel.MEDIUM)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(30))
                .idempotent(false)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.COLLECTING_DATASOURCES,
                        com.consilens.agent.api.state.AgentWorkflowStage.VALIDATING_CONNECTIONS,
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERING_METADATA,
                        com.consilens.agent.api.state.AgentWorkflowStage.DRAFTING_COMPARISON))
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<ProbeDatasourceInput> inputType() {
        return ProbeDatasourceInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("draftId", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("draftId"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<ProbeDatasourceOutput> execute(ProbeDatasourceInput input,
                                                           AgentToolContext context) {
        AgentDatasourceDraftState draft = findDraft(context.workingState(), input.getDraftId());
        if (draft == null || draft.getSecretRequestId() == null) {
            return AgentToolOutcome.error("DATASOURCE_NOT_FOUND", false,
                    "草稿不存在或未收集凭据: " + input.getDraftId());
        }
        String secretRequestId = draft.getSecretRequestId();
        Optional<char[]> secret = secretStore.resolve(secretRequestId,
                context.actorId(), context.sessionId(), "probe", Instant.now());
        if (secret.isEmpty()) {
            return AgentToolOutcome.error("SECRET_REQUEST_EXPIRED", false,
                    "凭据已过期或读取次数用尽，请重新提交");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (draft.getNonSecretParams() != null) {
            for (Map.Entry<String, AgentSlotValue> entry : draft.getNonSecretParams().entrySet()) {
                params.put(entry.getKey(), entry.getValue().getValue());
            }
        }
        char[] resolved = secret.get();
        String password = extractPassword(resolved);
        ConnectionTestRequest request = ConnectionTestRequest.builder()
                .type(draft.getType())
                .host(stringValue(params.get("host")))
                .port(integerValue(params.get("port")))
                .database(stringValue(params.get("database")))
                .username(stringValue(params.get("username")))
                .password(password)
                .options(optionsOf(params))
                .build();

        ConnectionTestResponse response = connectionTestService.test(request);
        String paramDigest = CanonicalJsonDigester.digest(MAPPER.valueToTree(params));
        if (response.isSuccess()) {
            return AgentToolOutcome.ok(
                    ProbeDatasourceOutput.builder()
                            .draftId(input.getDraftId())
                            .success(true)
                            .safeMessage("连接成功")
                            .paramDigest(paramDigest)
                            .build(),
                    "连接成功");
        }
        String error = response.getError() == null ? "" : response.getError();
        boolean authFailure = error.toLowerCase().contains("auth")
                || error.toLowerCase().contains("password")
                || error.toLowerCase().contains("access denied")
                || error.toLowerCase().contains("login");
        if (!authFailure) {
            return AgentToolOutcome.error("DATASOURCE_PROBE_FAILED", true, "连接失败: " + error);
        }
        // 认证失败：撤销旧请求并创建新的 secret request，让用户重填（设计 23.5）。
        secretStore.revoke(secretRequestId, context.actorId(), context.sessionId());
        ConsilensServerProperties.Ai ai = properties.getAi();
        Instant expiresAt = Instant.now().plusSeconds(ai.getSecretTtlSeconds());
        String newRequestId = secretStore.createRequest(context.actorId(), context.sessionId(),
                draft.getDraftId(), expiresAt, ai.getSecretMaxReads());
        return AgentToolOutcome.<ProbeDatasourceOutput>builder()
                .success(false)
                .errorCode("DATASOURCE_PROBE_AUTH_FAILED")
                .retryable(false)
                .content("认证失败，请在安全表单重新填写密码")
                .visibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .structuredData(ProbeDatasourceOutput.builder()
                        .draftId(input.getDraftId())
                        .success(false)
                        .errorCode("DATASOURCE_PROBE_AUTH_FAILED")
                        .newSecretRequestId(newRequestId)
                        .build())
                .secretInputRequired(true)
                .secretRequestId(newRequestId)
                .draftId(input.getDraftId())
                .secretExpiresAt(expiresAt)
                .build();
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, ProbeDatasourceOutput output) {
        AgentDraftSide side = output.getDraftId().equals(AgentDraftSide.SOURCE.draftId())
                ? AgentDraftSide.SOURCE : AgentDraftSide.TARGET;
        AgentDatasourceDraftState base = side == AgentDraftSide.SOURCE
                ? previous.getSource() : previous.getTarget();
        if (base == null) {
            return previous;
        }
        AgentDatasourceDraftState updated = base.toBuilder()
                .probeStatus(output.isSuccess()
                        ? AgentProbeStatus.SUCCEEDED : AgentProbeStatus.FAILED)
                .paramDigest(output.getParamDigest())
                .secretRequestId(output.getNewSecretRequestId() == null
                        ? base.getSecretRequestId() : output.getNewSecretRequestId())
                .build();
        return previous.toBuilder()
                .source(side == AgentDraftSide.SOURCE ? updated : previous.getSource())
                .target(side == AgentDraftSide.TARGET ? updated : previous.getTarget())
                .stage(previous.getStage().ordinal()
                        > AgentWorkflowStage.VALIDATING_CONNECTIONS.ordinal()
                        ? previous.getStage() : AgentWorkflowStage.VALIDATING_CONNECTIONS)
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

    private String extractPassword(char[] secretPayload) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(new String(secretPayload));
            if (node.has("password")) {
                return node.get("password").asText();
            }
            if (node.isObject() && node.size() > 0) {
                return node.fields().next().getValue().asText();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // fall through to raw value for backward compatibility
        }
        return new String(secretPayload);
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

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
