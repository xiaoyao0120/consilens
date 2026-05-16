package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.PlannerResult;
import com.consilens.ai.conversation.engine.model.PlannerRoute;
import com.consilens.ai.conversation.engine.model.PlannerType;
import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.spi.LLMBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPlannerAgentTest {

    @Test
    void shouldExecuteToolCallsBeforeParsingFinalPlannerResult() {
        RecordingBackend backend = new RecordingBackend();
        LlmPlannerAgent agent = new LlmPlannerAgent(
                backend,
                new PlannerPromptBuilder(),
                new JacksonPlannerResultParser(),
                new PlannerToolExecutor() {
                    @Override
                    public List<FunctionDefinition> functions() {
                        return List.of(FunctionDefinition.builder()
                                .name("get_session_state")
                                .description("Get current session state")
                                .build());
                    }

                    @Override
                    public String execute(String toolName, Map<String, Object> input, PlannerContext context) {
                        return "{\"status\":\"ready\"}";
                    }
                });

        PlannerResult result = agent.plan(PlannerContext.builder()
                .rawInput("我想比较 mysql 和 postgresql 中 users 表的数据")
                .build());

        assertEquals(PlannerType.PLAN, result.getType());
        assertEquals(PlannerRoute.PLAN_CONFIG, result.getRoute());
        assertFalse(result.isFallback());
        assertEquals(2, backend.calls.size());
        assertEquals(1, backend.calls.get(1).stream().filter(message -> message.getRole() == ChatMessage.Role.TOOL).count());
    }

    private static final class RecordingBackend implements LLMBackend {

        private final List<List<ChatMessage>> calls = new ArrayList<>();

        @Override
        public LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions) {
            calls.add(List.copyOf(messages));
            if (calls.size() == 1) {
                assertTrue(functions.stream().anyMatch(function -> "get_session_state".equals(function.getName())));
                return LLMResponse.builder()
                        .toolCall(ChatMessage.ToolCall.builder()
                                .id("call-1")
                                .name("get_session_state")
                                .arguments(Map.of("sessionId", "session-1"))
                                .build())
                        .finishReason("tool_calls")
                        .build();
            }
            return LLMResponse.builder()
                    .text("{\"type\":\"PLAN\",\"route\":\"PLAN_CONFIG\",\"normalizedGoal\":\"compare users\",\"extractedSlots\":{\"sourceType\":\"mysql\",\"targetType\":\"postgresql\",\"sourceTable\":\"users\",\"targetTable\":\"users\"},\"missingSlots\":[],\"assumptions\":[],\"question\":null,\"answer\":null,\"reasoning\":\"enough context\"}")
                    .finishReason("stop")
                    .build();
        }

        @Override
        public String complete(String prompt) {
            return "";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BackendInfo info() {
            return BackendInfo.builder().name("recording").build();
        }
    }
}
