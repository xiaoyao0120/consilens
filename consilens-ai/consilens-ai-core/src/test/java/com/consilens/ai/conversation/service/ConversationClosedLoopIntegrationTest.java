package com.consilens.ai.conversation.service;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.engine.DefaultApprovalManager;
import com.consilens.ai.conversation.engine.DefaultClarificationManager;
import com.consilens.ai.conversation.engine.DefaultConversationEngine;
import com.consilens.ai.conversation.engine.DefaultTurnPlanner;
import com.consilens.ai.conversation.engine.TaskActionExecutor;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.runtime.task.TaskRegistry;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiMemoryCandidate;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationClosedLoopIntegrationTest {

    @Test
    void shouldRunClosedLoopPlanCheckRunApproveDiagnoseRepair() {
        AtomicBoolean checkDryRunEnabled = new AtomicBoolean(false);
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        ConversationService service = new DefaultConversationService(
                new DefaultConversationEngine(
                        sessionStore,
                        new DefaultTurnPlanner((session, userInput) -> com.consilens.ai.runtime.intent.AiIntent.PLAN_CONFIG),
                        new DefaultClarificationManager(),
                        new DefaultApprovalManager(),
                        new TaskActionExecutor(taskRegistry(checkDryRunEnabled, sessionStore), sessionStore)),
                sessionStore,
                new InMemoryArtifactStore(),
                new InMemoryMemoryStore());

        String sessionId = service.startSession("loop-1", true).getSessionId();

        ConversationResponse plan = service.executeCommand(ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName("plan")
                .argument("compare mysql.orders -> postgresql.orders keys: id")
                .build());
        assertEquals(ConversationResponse.Type.MESSAGE, plan.getType());

        ConversationResponse check = service.executeCommand(ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName("check")
                .build());
        assertEquals(ConversationResponse.Type.MESSAGE, check.getType());
        assertTrue(checkDryRunEnabled.get());

        ConversationResponse run = service.executeCommand(ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName("run")
                .build());
        assertEquals(ConversationResponse.Type.APPROVAL, run.getType());

        ConversationResponse approve = service.approve(sessionId);
        assertEquals(ConversationResponse.Type.MESSAGE, approve.getType());

        ConversationResponse diagnose = service.executeCommand(ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName("diagnose")
                .build());
        assertEquals(ConversationResponse.Type.MESSAGE, diagnose.getType());

        ConversationResponse repair = service.executeCommand(ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName("repair")
                .build());
        assertEquals(ConversationResponse.Type.MESSAGE, repair.getType());
    }

    private TaskRegistry taskRegistry(AtomicBoolean checkDryRunEnabled, InMemorySessionStore sessionStore) {
        Map<AiTaskType, AiTask> tasks = new HashMap<>();
        tasks.put(AiTaskType.PLAN_CONFIG, new AiTask() {
            @Override
            public AiTaskType type() {
                return AiTaskType.PLAN_CONFIG;
            }

            @Override
            public AiTaskResult execute(AiTaskContext context) {
                AiSession session = context.getSession().toBuilder()
                        .currentConfigArtifactId("config-1")
                        .updatedAt(Instant.now())
                        .build();
                sessionStore.save(session);
                return AiTaskResult.builder()
                        .success(true)
                        .taskType(AiTaskType.PLAN_CONFIG)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary("planned")
                        .suggestedNextAction("check")
                        .build();
            }
        });
        tasks.put(AiTaskType.DRY_RUN, new AiTask() {
            @Override
            public AiTaskType type() {
                return AiTaskType.DRY_RUN;
            }

            @Override
            public AiTaskResult execute(AiTaskContext context) {
                Boolean performDryRun = context.attribute("performDryRun", Boolean.class);
                checkDryRunEnabled.set(Boolean.TRUE.equals(performDryRun));
                return AiTaskResult.builder()
                        .success(true)
                        .taskType(AiTaskType.DRY_RUN)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary("checked")
                        .suggestedNextAction("run")
                        .build();
            }
        });
        tasks.put(AiTaskType.RUN_DIFF, new AiTask() {
            @Override
            public AiTaskType type() {
                return AiTaskType.RUN_DIFF;
            }

            @Override
            public AiTaskResult execute(AiTaskContext context) {
                Boolean approved = context.attribute("approveExecute", Boolean.class);
                if (!Boolean.TRUE.equals(approved)) {
                    return AiTaskResult.builder()
                            .success(false)
                            .taskType(AiTaskType.RUN_DIFF)
                            .status(AiTurnResult.Status.REQUIRES_APPROVAL)
                            .summary("approval needed")
                            .suggestedNextAction("run")
                            .build();
                }
                return AiTaskResult.builder()
                        .success(true)
                        .taskType(AiTaskType.RUN_DIFF)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary("run completed")
                        .suggestedNextAction("diagnose")
                        .build();
            }
        });
        tasks.put(AiTaskType.DIAGNOSE, successTask(AiTaskType.DIAGNOSE, "diagnosed"));
        tasks.put(AiTaskType.REPAIR, successTask(AiTaskType.REPAIR, "repaired"));
        tasks.put(AiTaskType.DOCTOR, successTask(AiTaskType.DOCTOR, "doctor"));
        return type -> Optional.ofNullable(tasks.get(type));
    }

    private AiTask successTask(AiTaskType taskType, String summary) {
        return new AiTask() {
            @Override
            public AiTaskType type() {
                return taskType;
            }

            @Override
            public AiTaskResult execute(AiTaskContext context) {
                return AiTaskResult.builder()
                        .success(true)
                        .taskType(taskType)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary(summary)
                        .suggestedNextAction("next")
                        .build();
            }
        };
    }

    private static class InMemorySessionStore implements AiSessionStore {
        private final Map<String, AiSession> sessions = new HashMap<>();

        @Override
        public Optional<AiSession> load(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public AiSession create(String preferredSessionId) {
            String id = preferredSessionId == null ? "default" : preferredSessionId;
            AiSession session = AiSession.builder()
                    .sessionId(id)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .status("ready")
                    .currentTask("doctor")
                    .build();
            sessions.put(id, session);
            return session;
        }

        @Override
        public void save(AiSession session) {
            sessions.put(session.getSessionId(), session);
        }

        @Override
        public List<AiSession> list() {
            return sessions.values().stream().collect(Collectors.toList());
        }
    }

    private static class InMemoryArtifactStore implements AiArtifactStore {
        @Override
        public ArtifactRef write(String sessionId, ArtifactType type, byte[] content, Map<String, String> metadata) {
            return ArtifactRef.builder()
                    .artifactId("artifact")
                    .sessionId(sessionId)
                    .type(type)
                    .path("/tmp/artifact")
                    .createdAt(Instant.now())
                    .metadata(metadata)
                    .build();
        }

        @Override
        public Optional<ArtifactRef> get(String artifactId) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> read(String artifactId) {
            return Optional.empty();
        }

        @Override
        public Optional<ArtifactRef> latest(String sessionId, ArtifactType type) {
            return Optional.empty();
        }

        @Override
        public List<ArtifactRef> list(String sessionId) {
            return List.of();
        }
    }

    private static class InMemoryMemoryStore implements AiMemoryStore {
        @Override
        public List<AiMemory> list() {
            return List.of();
        }

        @Override
        public Optional<AiMemory> add(AiMemoryCandidate candidate) {
            return Optional.empty();
        }

        @Override
        public void remove(String memoryId) {
        }
    }
}
