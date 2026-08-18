package com.consilens.agent.core.tool;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentProbeStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentTool;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolDescriptor;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.ToolExecutionMode;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.api.tool.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;

/**
 * B3 regression tool: a probe that fails authentication, revokes the old
 * secret request and carries the NEW request id in its structured output so
 * the reducer can migrate the draft even though the outcome is a failure.
 */
public final class SecretRefillTool implements AgentTool<SecretRefillInput, SecretRefillOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("secret_refill")
                .description("probe that fails and requests a new secret")
                .riskLevel(ToolRiskLevel.MEDIUM)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(10))
                .idempotent(false)
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<SecretRefillInput> inputType() {
        return SecretRefillInput.class;
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
    public AgentToolOutcome<SecretRefillOutput> execute(SecretRefillInput input, AgentToolContext context) {
        SecretRefillOutput output = SecretRefillOutput.builder()
                .draftId(input.getDraftId())
                .newSecretRequestId("new-sec-2")
                .build();
        return AgentToolOutcome.<SecretRefillOutput>builder()
                .success(false)
                .errorCode("DATASOURCE_PROBE_AUTH_FAILED")
                .retryable(false)
                .content("认证失败，请重新填写密码")
                .visibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .structuredData(output)
                .secretInputRequired(true)
                .secretRequestId("new-sec-2")
                .draftId(input.getDraftId())
                .missingSlots(List.of("password"))
                .build();
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, SecretRefillOutput output) {
        AgentDatasourceDraftState base = "draft_source".equals(output.getDraftId())
                ? previous.getSource() : previous.getTarget();
        if (base == null) {
            return previous;
        }
        AgentDatasourceDraftState updated = base.toBuilder()
                .secretRequestId(output.getNewSecretRequestId())
                .probeStatus(AgentProbeStatus.FAILED)
                .build();
        return previous.toBuilder()
                .source("draft_source".equals(output.getDraftId()) ? updated : previous.getSource())
                .target("draft_target".equals(output.getDraftId()) ? updated : previous.getTarget())
                .stage(previous.getStage().ordinal()
                        > AgentWorkflowStage.VALIDATING_CONNECTIONS.ordinal()
                        ? previous.getStage() : AgentWorkflowStage.VALIDATING_CONNECTIONS)
                .build();
    }
}
