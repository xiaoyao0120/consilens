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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.consilens.server.application.ai.plan.CreateDatasourceCommandHandler.findDraft;
import static com.consilens.server.application.ai.tool.SecretPayloadCodec.MAPPER;

/**
 * {@code probe_datasource}: resolves the out-of-band secret for a draft and
 * runs the existing connection test without creating the datasource. The
 * secret never enters tool arguments or the model context.
 */
public final class ProbeDatasourceTool implements AgentTool<ProbeDatasourceInput, ProbeDatasourceOutput> {


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
                .sideEffect(ToolSideEffect.READ)
                .idempotent(false)
                .allowedStages(java.util.Set.of(
                        com.consilens.agent.api.state.AgentWorkflowStage.COLLECTING_DATASOURCES,
                        com.consilens.agent.api.state.AgentWorkflowStage.VALIDATING_CONNECTIONS,
                        com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERING_METADATA,
                        com.consilens.agent.api.state.AgentWorkflowStage.DRAFTING_COMPARISON))
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
        String password = SecretPayloadCodec.extract(resolved, "password");
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
        if (!isAuthenticationFailure(response)) {
            String error = response.getError() == null ? "" : response.getError();
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

    /**
     * 判断连接测试失败是否属于“凭据被拒绝”。
     *
     * <p>优先遍历异常链上的 {@link SQLException#getSQLState()}：
     * <ul>
     *   <li>{@code 28xxx}（invalid authorization specification，如 28000 / 28P01）→ 认证失败；</li>
     *   <li>{@code 08xxx}（connection exception）默认是网络/服务不可达；只有驱动把登录失败
     *       归入该类且异常消息命中凭据关键词时才算认证失败；</li>
     *   <li>{@link ConnectException}、{@link UnknownHostException}、{@link SocketTimeoutException}
     *       一律不算认证失败。</li>
     * </ul>
     * 部分驱动不返回 SQLState（或把登录失败包成普通 RuntimeException），此时才回退到
     * 错误文案匹配；文案匹配只保留在本方法，作为最后一级兜底。
     */
    static boolean isAuthenticationFailure(ConnectionTestResponse response) {
        if (response == null) {
            return false;
        }
        boolean sawSqlState = false;
        Throwable previous = null;
        for (Throwable current = response.getCause();
             current != null && current != previous;
             previous = current, current = current.getCause()) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof SocketTimeoutException) {
                return false;
            }
            if (current instanceof SQLException) {
                String sqlState = ((SQLException) current).getSQLState();
                if (sqlState != null) {
                    sawSqlState = true;
                    if (sqlState.startsWith("28")) {
                        return true;
                    }
                    // 08 类是连接异常：只有驱动同时把凭据失败信息写进消息才算认证失败。
                    if (sqlState.startsWith("08") && mentionsCredentials(current.getMessage())) {
                        return true;
                    }
                }
            }
        }
        // 最后一级兜底：驱动既不提供 SQLState，也没有可识别的异常类型。
        return !sawSqlState && mentionsCredentials(response.getError());
    }

    /** 凭据相关的文案关键词（仅用于无 SQLState 可判定的驱动）。 */
    private static boolean mentionsCredentials(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("auth")
                || lower.contains("password")
                || lower.contains("access denied")
                || lower.contains("login")
                || lower.contains("credential")
                || lower.contains("invalid user");
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
