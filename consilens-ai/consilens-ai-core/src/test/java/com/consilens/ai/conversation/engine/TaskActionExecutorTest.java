package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskActionExecutorTest {

    @Test
    void shouldReturnFailedTaskResultWhenTaskThrows() {
        TaskActionExecutor executor = new TaskActionExecutor(
                type -> Optional.of(new AiTask() {
                    @Override
                    public AiTaskType type() {
                        return AiTaskType.PLAN_CONFIG;
                    }

                    @Override
                    public AiTaskResult execute(AiTaskContext context) {
                        throw new IllegalStateException("boom");
                    }
                }),
                new InMemorySessionStore());

        AiTaskResult result = executor.execute(ActionPlan.builder()
                .sessionId("s1")
                .commandName("plan")
                .build());

        assertFalse(result.isSuccess());
        assertEquals(AiTurnResult.Status.FAILED, result.getStatus());
        assertEquals(AiTaskType.PLAN_CONFIG, result.getTaskType());
    }

    @Test
    void shouldMapCheckToDryRunTaskType() {
        AtomicReference<AiTaskContext> captured = new AtomicReference<>();
        TaskActionExecutor executor = new TaskActionExecutor(
                new CapturingTaskRegistry(captured),
                new InMemorySessionStore());

        AiTaskResult result = executor.execute(ActionPlan.builder()
                .sessionId("s2")
                .commandName("check")
                .build());

        assertEquals(AiTaskType.DRY_RUN, result.getTaskType());
        assertTrue(captured.get().attribute("performDryRun", Boolean.class));
        assertTrue(captured.get().attribute("inlineOutput", Boolean.class));
    }

    @Test
    void shouldMapAnalyzeLastToDiagnoseTaskType() {
        TaskActionExecutor executor = new TaskActionExecutor(
                new CapturingTaskRegistry(new AtomicReference<>()),
                new InMemorySessionStore());

        AiTaskResult result = executor.execute(ActionPlan.builder()
                .sessionId("s3")
                .commandName("analyze-last")
                .build());

        assertEquals(AiTaskType.DIAGNOSE, result.getTaskType());
    }

    @Test
    void shouldMapRememberAndForgetToMemoryTasks() {
        TaskActionExecutor executor = new TaskActionExecutor(
                new CapturingTaskRegistry(new AtomicReference<>()),
                new InMemorySessionStore());

        AiTaskResult remember = executor.execute(ActionPlan.builder()
                .sessionId("s4")
                .commandName("remember")
                .build());
        AiTaskResult forget = executor.execute(ActionPlan.builder()
                .sessionId("s5")
                .commandName("forget")
                .build());

        assertEquals(AiTaskType.MEMORY_ADD, remember.getTaskType());
        assertEquals(AiTaskType.MEMORY_REMOVE, forget.getTaskType());
    }

    private static class CapturingTaskRegistry implements TaskRegistry {
        private final AtomicReference<AiTaskContext> captured;

        private CapturingTaskRegistry(AtomicReference<AiTaskContext> captured) {
            this.captured = captured;
        }

        @Override
        public Optional<AiTask> get(AiTaskType type) {
            return Optional.of(new AiTask() {
                @Override
                public AiTaskType type() {
                    return type;
                }

                @Override
                public AiTaskResult execute(AiTaskContext context) {
                    captured.set(context);
                    return AiTaskResult.builder()
                            .success(true)
                            .taskType(type)
                            .status(AiTurnResult.Status.COMPLETED)
                            .summary("ok")
                            .build();
                }
            });
        }
    }

    private static class InMemorySessionStore implements AiSessionStore {
        @Override
        public Optional<AiSession> load(String sessionId) {
            return Optional.empty();
        }

        @Override
        public AiSession create(String preferredSessionId) {
            return AiSession.builder()
                    .sessionId(preferredSessionId == null ? "default" : preferredSessionId)
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
