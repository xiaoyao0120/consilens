package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerPromptBuilderTest {

    private final PlannerPromptBuilder builder = new PlannerPromptBuilder();

    @Test
    void shouldDescribePlannerClassificationContract() {
        String prompt = builder.buildSystemPrompt(PlannerContext.builder().build());

        assertTrue(prompt.contains("Your job is NOT to generate YAML directly."));
        assertTrue(prompt.contains("Output CHAT for greetings, help, conceptual questions, connector comparisons"));
        assertTrue(prompt.contains("asks for a template/example/sample"));
        assertTrue(prompt.contains("For DIAGNOSE/REPAIR routes, only output PLAN when there is concrete evidence context"));
        assertTrue(prompt.contains("If the user is questioning planner behavior"));
        assertTrue(prompt.contains("ROUTING STRATEGY:"));
        assertTrue(prompt.contains("\"type\": \"PLAN|CHAT|QUESTION|ERROR\""));
        assertTrue(prompt.contains("Do NOT interrupt the user for these non-critical items"));
    }

    @Test
    void shouldIncludeSessionAndAttributeContextInUserPrompt() {
        AiSession session = AiSession.builder()
                .sessionId("s-1")
                .status("planned")
                .currentTask("plan")
                .currentObjective("compare users")
                .currentConfigArtifactId("config-1")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        String prompt = builder.buildUserPrompt(PlannerContext.builder()
                .session(session)
                .rawInput("我想比较 mysql 和 postgresql 中 users 表的数据")
                .commandName("plan")
                .commandArgument("compare users")
                .attribute("startupBackend", "openai")
                .attribute("startupModel", "gpt-4o")
                .build());

        assertTrue(prompt.contains("Current request:"));
        assertTrue(prompt.contains("sessionId=s-1"));
        assertTrue(prompt.contains("currentConfigArtifactId=config-1"));
        assertTrue(prompt.contains("name=plan"));
        assertTrue(prompt.contains("startupBackend=openai"));
        assertTrue(prompt.contains("startupModel=gpt-4o"));
    }
}
