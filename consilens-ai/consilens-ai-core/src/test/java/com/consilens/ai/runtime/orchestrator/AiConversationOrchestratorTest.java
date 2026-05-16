package com.consilens.ai.runtime.orchestrator;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.intent.AiIntent;
import com.consilens.ai.runtime.intent.IntentRouter;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.runtime.task.TaskRegistry;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiConversationOrchestratorTest {

    @Test
    void shouldAttachConfigRequestForNaturalLanguagePlanInput() {
        AtomicReference<AiTaskContext> captured = new AtomicReference<>();
        AiConversationOrchestrator orchestrator = new AiConversationOrchestrator(
                (session, userInput) -> AiIntent.PLAN_CONFIG,
                taskRegistry(captured),
                new StubSessionStore());

        AiTurnResult result = orchestrator.handleUserInput("session-1", "compare orders");

        assertEquals(AiTurnResult.Status.COMPLETED, result.getStatus());
        ConfigGenerationRequest request = captured.get().attribute("configRequest", ConfigGenerationRequest.class);
        assertEquals("session-1", request.getSessionId());
        assertEquals("compare orders", request.getGoal());
    }

    @Test
    void shouldAttachConfigRequestForNaturalLanguageRunInput() {
        AtomicReference<AiTaskContext> captured = new AtomicReference<>();
        AiConversationOrchestrator orchestrator = new AiConversationOrchestrator(
                (session, userInput) -> AiIntent.RUN_DIFF,
                taskRegistry(captured),
                new StubSessionStore());

        AiTurnResult result = orchestrator.handleUserInput("session-2", "run the comparison");

        assertEquals(AiTurnResult.Status.COMPLETED, result.getStatus());
        ConfigGenerationRequest request = captured.get().attribute("configRequest", ConfigGenerationRequest.class);
        assertEquals("session-2", request.getSessionId());
        assertEquals("run the comparison", request.getGoal());
    }

    private TaskRegistry taskRegistry(AtomicReference<AiTaskContext> captured) {
        return type -> Optional.of(new AiTask() {
            @Override
            public AiTaskType type() {
                return AiTaskType.PLAN_CONFIG;
            }

            @Override
            public AiTaskResult execute(AiTaskContext context) {
                captured.set(context);
                return AiTaskResult.builder()
                        .success(true)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary("ok")
                        .build();
            }
        });
    }

    private static class StubSessionStore implements AiSessionStore {

        @Override
        public Optional<AiSession> load(String sessionId) {
            return Optional.empty();
        }

        @Override
        public AiSession create(String preferredSessionId) {
            return AiSession.builder()
                    .sessionId(preferredSessionId)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .status("ready")
                    .currentTask("doctor")
                    .build();
        }

        @Override
        public void save(AiSession session) {
        }

        @Override
        public List<AiSession> list() {
            return List.of();
        }
    }
}
