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
import java.util.List;

/**
 * Control tool that requests structured user input (section 22.4 semantics).
 */
public final class QuestionTool implements AgentTool<QuestionInput, QuestionOutput> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public AgentToolDescriptor descriptor() {
        return AgentToolDescriptor.builder()
                .name("request_user_input")
                .description("requests missing slot values from the user")
                .riskLevel(ToolRiskLevel.LOW)
                .executionMode(ToolExecutionMode.SEQUENTIAL)
                .sideEffect(ToolSideEffect.CONTROL)
                .timeout(Duration.ofSeconds(5))
                .idempotent(false)
                .allowedStages(java.util.Set.of())
                .resultVisibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .build();
    }

    @Override
    public Class<QuestionInput> inputType() {
        return QuestionInput.class;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode properties = MAPPER.createObjectNode();
        properties.set("question", MAPPER.createObjectNode().put("type", "string"));
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", MAPPER.createArrayNode().add("question"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public AgentToolOutcome<QuestionOutput> execute(QuestionInput input, AgentToolContext context) {
        return AgentToolOutcome.<QuestionOutput>builder()
                .success(true)
                .structuredData(QuestionOutput.builder().question(input.getQuestion()).build())
                .content(input.getQuestion())
                .visibility(com.consilens.agent.api.tool.ToolResultVisibility.MODEL_AND_USER)
                .questionRequired(true)
                .question(input.getQuestion())
                .missingSlots(List.of("host"))
                .build();
    }

    @Override
    public AgentWorkingState reduce(AgentWorkingState previous, QuestionOutput output) {
        return previous.toBuilder()
                .unresolvedQuestions(List.of(output.getQuestion()))
                .build();
    }
}
