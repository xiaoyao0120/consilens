package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.PlannerResult;
import com.consilens.ai.conversation.engine.model.PlannerRoute;
import com.consilens.ai.conversation.engine.model.PlannerType;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.spi.LLMBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * LLM-backed planner. All routing decisions go through the LLM.
 * If the LLM is unavailable or returns no response, an ERROR result is returned.
 */
public class LlmPlannerAgent implements PlannerAgent {

    private final Function<PlannerContext, LLMBackend> backendResolver;
    private final PlannerPromptBuilder promptBuilder;
    private final PlannerResultParser resultParser;
    private final PlannerToolExecutor toolExecutor;

    public LlmPlannerAgent(LLMBackend backend,
                           PlannerPromptBuilder promptBuilder,
                           PlannerResultParser resultParser) {
        this(context -> backend, promptBuilder, resultParser, null);
    }

    public LlmPlannerAgent(LLMBackend backend,
                           PlannerPromptBuilder promptBuilder,
                           PlannerResultParser resultParser,
                           PlannerToolExecutor toolExecutor) {
        this(context -> backend, promptBuilder, resultParser, toolExecutor);
    }

    public LlmPlannerAgent(Function<PlannerContext, LLMBackend> backendResolver,
                           PlannerPromptBuilder promptBuilder,
                           PlannerResultParser resultParser,
                           PlannerToolExecutor toolExecutor) {
        this.backendResolver = backendResolver;
        this.promptBuilder = promptBuilder;
        this.resultParser = resultParser;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public PlannerResult plan(PlannerContext context) {
        try {
            LLMBackend backend = backendResolver.apply(context);
            if (backend == null) {
                throw new IllegalStateException("Planner backend resolver returned null.");
            }
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user(promptBuilder.buildUserPrompt(context)));
            for (int round = 0; round < 5; round++) {
                LLMResponse response = backend.chat(
                        promptBuilder.buildSystemPrompt(context),
                        messages,
                        toolExecutor == null ? List.of() : toolExecutor.functions());
                if (response == null) {
                    throw new IllegalStateException("Planner backend returned no response.");
                }
                if (response.isError()) {
                    throw new IllegalStateException(response.getText());
                }
                if (response.hasToolCalls() && toolExecutor != null) {
                    messages.add(ChatMessage.assistantWithToolCalls(response.getText(), response.getToolCalls()));
                    for (ChatMessage.ToolCall toolCall : response.getToolCalls()) {
                        String content = toolExecutor.execute(toolCall.getName(), toolCall.getArguments(), context);
                        messages.add(ChatMessage.toolResult(toolCall.getId(), content));
                    }
                    continue;
                }
                String payload = response.getText();
                if (payload == null || payload.isBlank()) {
                    throw new IllegalStateException("Planner backend returned an empty response.");
                }
                return resultParser.parse(payload);
            }
            throw new IllegalStateException("Planner exceeded maximum tool rounds.");
        } catch (RuntimeException e) {
            return PlannerResult.builder()
                    .type(PlannerType.ERROR)
                    .route(PlannerRoute.CHAT)
                    .answer("LLM planner failed: " + e.getMessage())
                    .reasoning("llm-error")
                    .build();
        }
    }
}
