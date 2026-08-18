package com.consilens.agent.core.tool;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only echo tool used by WP-05 loop tests. Its reducer records the
 * echoed value as a confirmed fact and advances the checkpoint.
 */
public class EchoTool implements AgentTool<EchoInput, EchoOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("echo")
                .description("echoes the given text back")
                .riskLevel(ToolRiskLevel.LOW)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.READ)
                .timeout(Duration.ofSeconds(10))
                .idempotent(false)
                .allowedStages(java.util.Set.of())
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<EchoInput> inputType() {
        return EchoInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("text", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("text"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<EchoOutput> execute(EchoInput input, AgentToolContext context) {
        return AgentToolOutcome.ok(
                EchoOutput.builder().echoed(input.getText()).build(),
                "echoed: " + input.getText());
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, EchoOutput output) {
        List<String> assumptions = new ArrayList<>(previous.getConfirmedAssumptions());
        assumptions.add("echo:" + output.getEchoed());
        return previous.toBuilder()
                .confirmedAssumptions(assumptions)
                .lastCompletedStep("ECHO")
                .stage(AgentWorkflowStage.COLLECTING_DATASOURCES)
                .build();
    }
}
