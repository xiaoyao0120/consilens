package com.consilens.ai.tool;

import com.consilens.ai.chat.ConversationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerSessionToolsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldReturnSessionStateFromContextAttributes() {
        PlannerSessionStateTool tool = new PlannerSessionStateTool();

        ToolResult result = tool.execute(input(), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredData().toString()).contains("currentConfigArtifactId");
        assertThat(result.getStructuredData().toString()).contains("hasConfig=true");
    }

    @Test
    void shouldReturnCurrentConfigSummaryAndContent() {
        PlannerCurrentConfigTool tool = new PlannerCurrentConfigTool();

        ToolResult result = tool.execute(input(), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredData().toString()).contains("source:");
        assertThat(result.getStructuredData().toString()).contains("mysql");
    }

    @Test
    void shouldReturnMemoryFactsFromContextAttributes() {
        PlannerMemoryFactsTool tool = new PlannerMemoryFactsTool();

        ToolResult result = tool.execute(input(), context());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStructuredData().toString()).contains("users.id");
    }

    private ObjectNode input() {
        ObjectNode input = OBJECT_MAPPER.createObjectNode();
        input.put("sessionId", "session-1");
        return input;
    }

    private ToolContext context() {
        return ToolContext.builder()
                .conversation(new ConversationContext())
                .attributes(Map.of(
                        "status", "ready",
                        "currentTask", "plan",
                        "currentObjective", "compare users",
                        "currentConfigArtifactId", "config-1",
                        "latestRunArtifactId", "run-1",
                        "currentConfigSummary", "source: mysql users -> target: postgresql users",
                        "currentConfigContent", "source:\n  type: mysql\n  resource:\n    table: users\n",
                        "memoryFacts", List.of(Map.of("type", "key", "content", "users.id"))))
                .build();
    }
}
