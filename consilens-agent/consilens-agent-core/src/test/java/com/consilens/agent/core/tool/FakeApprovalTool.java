package com.consilens.agent.core.tool;

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

/**
 * WP-09 fake write tool: requires structured approval before any write would
 * happen. Used to prove the loop suspends with APPROVAL_REQUIRED and never
 * executes the write itself.
 */
public final class FakeApprovalTool implements AgentTool<FakeApprovalInput, FakeApprovalOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("fake_write")
                .description("fake write tool requiring approval")
                .riskLevel(ToolRiskLevel.HIGH)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.WRITE)
                .timeout(Duration.ofSeconds(5))
                .idempotent(false)
                .allowedStages(java.util.Set.of())
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<FakeApprovalInput> inputType() {
        return FakeApprovalInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("name", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("name"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<FakeApprovalOutput> execute(FakeApprovalInput input, AgentToolContext context) {
        return AgentToolOutcome.<FakeApprovalOutput>builder()
                .success(true)
                .structuredData(FakeApprovalOutput.builder().approved(false).build())
                .content("需要审批: " + input.getName())
                .visibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .approvalRequired(true)
                .approvalSummary("创建资源 " + input.getName())
                .approvalId("ap_fake_1")
                .actionDigest("digest-fake")
                .build();
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, FakeApprovalOutput output) {
        return previous;
    }
}
