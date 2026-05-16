package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.tool.Tool;
import com.consilens.ai.tool.ToolContext;
import com.consilens.ai.tool.ToolRegistry;
import com.consilens.ai.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryPlannerToolExecutorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldExposePlannerAttributesToToolContext() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "echo_context";
            }

            @Override
            public String getDescription() {
                return "Echo planner context";
            }

            @Override
            public JsonNode getInputSchema() {
                return OBJECT_MAPPER.createObjectNode().put("type", "object");
            }

            @Override
            public boolean isReadOnly() {
                return true;
            }

            @Override
            public ToolResult execute(JsonNode input, ToolContext context) {
                return ToolResult.success("ok", Map.of(
                        "status", context.getAttributes().get("status"),
                        "facts", context.getAttributes().get("memoryFacts")));
            }
        });

        ToolRegistryPlannerToolExecutor executor = new ToolRegistryPlannerToolExecutor(
                registry,
                context -> Map.of(
                        "status", "ready",
                        "memoryFacts", List.of(Map.of("type", "hint", "content", "users.id"))));

        String payload = executor.execute("echo_context", Map.of(), PlannerContext.builder().rawInput("compare").build());

        assertTrue(payload.contains("\"status\":\"ready\""));
        assertTrue(payload.contains("users.id"));
    }
}
