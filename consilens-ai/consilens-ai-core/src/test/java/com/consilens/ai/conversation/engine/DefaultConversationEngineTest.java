package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.conversation.engine.model.ActionType;
import com.consilens.ai.conversation.engine.model.PlannerContext;
import com.consilens.ai.conversation.engine.model.QuestionSpec;
import com.consilens.ai.conversation.engine.model.TurnDecision;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConversationEngineTest {

    @Test
    void shouldPersistPendingApprovalAndApproveLater() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        AtomicBoolean approvedExecution = new AtomicBoolean(false);
        DefaultConversationEngine engine = new DefaultConversationEngine(
                sessionStore,
                context -> TurnDecision.builder()
                        .type(TurnDecision.Type.ACTION)
                        .actionPlan(ActionPlan.builder()
                                .sessionId(context.getSession().getSessionId())
                                .commandName("run")
                                .actionType(ActionType.RUN_DIFF)
                                .build())
                        .build(),
                new DefaultClarificationManager(),
                new DefaultApprovalManager(),
                plan -> {
                    approvedExecution.set(plan.isRequiresApproval());
                    return AiTaskResult.builder()
                            .success(plan.isRequiresApproval())
                            .taskType(AiTaskType.RUN_DIFF)
                            .status(plan.isRequiresApproval() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.REQUIRES_APPROVAL)
                            .summary(plan.isRequiresApproval() ? "executed" : "approval needed")
                            .suggestedNextAction("run")
                            .build();
                });

        ConversationResponse first = engine.handleUserTurn("session-1", "run it");

        assertEquals(ConversationResponse.Type.APPROVAL, first.getType());
        assertNotNull(sessionStore.load("session-1").orElseThrow().getPendingApproval());

        ConversationResponse approved = engine.approve("session-1");

        assertEquals(ConversationResponse.Type.MESSAGE, approved.getType());
        assertTrue(approvedExecution.get());
        assertNull(sessionStore.load("session-1").orElseThrow().getPendingApproval());
    }

    @Test
    void shouldRequireApproveExecutePhraseWhilePendingApproval() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        AtomicBoolean approvedExecution = new AtomicBoolean(false);
        DefaultConversationEngine engine = new DefaultConversationEngine(
                sessionStore,
                context -> TurnDecision.builder()
                        .type(TurnDecision.Type.ACTION)
                        .actionPlan(ActionPlan.builder()
                                .sessionId(context.getSession().getSessionId())
                                .commandName("run")
                                .actionType(ActionType.RUN_DIFF)
                                .build())
                        .build(),
                new DefaultClarificationManager(),
                new DefaultApprovalManager(),
                plan -> {
                    approvedExecution.set(plan.isRequiresApproval());
                    return AiTaskResult.builder()
                            .success(plan.isRequiresApproval())
                            .taskType(AiTaskType.RUN_DIFF)
                            .status(plan.isRequiresApproval() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.REQUIRES_APPROVAL)
                            .summary(plan.isRequiresApproval() ? "executed" : "About to execute diff:\nType: /approve execute")
                            .suggestedNextAction("run")
                            .build();
                });

        ConversationResponse first = engine.handleUserTurn("session-phrase-1", "run it");
        assertEquals(ConversationResponse.Type.APPROVAL, first.getType());

        ConversationResponse looseReply = engine.handleUserTurn("session-phrase-1", "yes");
        assertEquals(ConversationResponse.Type.APPROVAL, looseReply.getType());
        assertNotNull(sessionStore.load("session-phrase-1").orElseThrow().getPendingApproval());
        assertFalse(approvedExecution.get());

        ConversationResponse approved = engine.handleUserTurn("session-phrase-1", "approve execute");
        assertEquals(ConversationResponse.Type.MESSAGE, approved.getType());
        assertTrue(approvedExecution.get());
        assertNull(sessionStore.load("session-phrase-1").orElseThrow().getPendingApproval());
    }

    @Test
    void shouldPersistClarificationAndMergeAnswer() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        DefaultConversationEngine engine = new DefaultConversationEngine(
                sessionStore,
                context -> {
                    if ("compare".equals(context.getRawInput())) {
                        return TurnDecision.builder()
                                .type(TurnDecision.Type.QUESTION)
                                .question(QuestionSpec.builder()
                                        .question("Need keys")
                                        .originalRequest("compare")
                                        .expectedKey("keys")
                                        .blocking(true)
                                        .build())
                                .build();
                    }
                    return TurnDecision.builder()
                            .type(TurnDecision.Type.ACTION)
                            .actionPlan(ActionPlan.builder()
                                    .sessionId(context.getSession().getSessionId())
                                    .commandName("plan")
                                    .commandArgument(context.getRawInput())
                                    .actionType(ActionType.PLAN_CONFIG)
                                    .build())
                            .build();
                },
                new DefaultClarificationManager(),
                new DefaultApprovalManager(),
                plan -> AiTaskResult.builder()
                        .success(true)
                        .taskType(AiTaskType.PLAN_CONFIG)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary(plan.getCommandArgument())
                        .suggestedNextAction("run")
                        .build());

        ConversationResponse question = engine.handleUserTurn("session-2", "compare");

        assertEquals(ConversationResponse.Type.QUESTION, question.getType());
        assertNotNull(sessionStore.load("session-2").orElseThrow().getPendingQuestion());

        ConversationResponse answer = engine.handleUserTurn("session-2", "keys=order_id");

        assertEquals(ConversationResponse.Type.MESSAGE, answer.getType());
        assertTrue(answer.getMessage().contains("keys=order_id"));
        assertNull(sessionStore.load("session-2").orElseThrow().getPendingQuestion());
    }

    @Test
    void shouldKeepQuestionWhenClarificationFormatIsInvalid() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        DefaultConversationEngine engine = new DefaultConversationEngine(
                sessionStore,
                context -> {
                    if ("compare".equals(context.getRawInput())) {
                        return TurnDecision.builder()
                                .type(TurnDecision.Type.QUESTION)
                                .question(QuestionSpec.builder()
                                        .question("Need keys")
                                        .originalRequest("compare")
                                        .expectedKey("keys")
                                        .blocking(true)
                                        .build())
                                .build();
                    }
                    return TurnDecision.builder()
                            .type(TurnDecision.Type.MESSAGE)
                            .message("ok")
                            .build();
                },
                new DefaultClarificationManager(),
                new DefaultApprovalManager(),
                plan -> AiTaskResult.builder()
                        .success(true)
                        .taskType(AiTaskType.PLAN_CONFIG)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary(plan.getCommandArgument())
                        .suggestedNextAction("run")
                        .build());

        ConversationResponse question = engine.handleUserTurn("session-invalid", "compare");
        assertEquals(ConversationResponse.Type.QUESTION, question.getType());

        ConversationResponse invalid = engine.handleUserTurn("session-invalid", "keys=");
        assertEquals(ConversationResponse.Type.QUESTION, invalid.getType());
        assertTrue(invalid.getMessage().contains("[输入校验]"));
        assertNotNull(sessionStore.load("session-invalid").orElseThrow().getPendingQuestion());
    }

    @Test
    void shouldReturnErrorResponseWhenActionExecutionThrows() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        DefaultConversationEngine engine = new DefaultConversationEngine(
                sessionStore,
                context -> TurnDecision.builder()
                        .type(TurnDecision.Type.ACTION)
                        .actionPlan(ActionPlan.builder()
                                .sessionId(context.getSession().getSessionId())
                                .commandName("plan")
                                .actionType(ActionType.PLAN_CONFIG)
                                .build())
                        .build(),
                new DefaultClarificationManager(),
                new DefaultApprovalManager(),
                plan -> {
                    throw new IllegalStateException("explode");
                });

        ConversationResponse response = engine.handleUserTurn("session-3", "plan it");

        assertEquals(ConversationResponse.Type.ERROR, response.getType());
        assertTrue(response.getMessage().contains("Action execution failed"));
    }

    @Test
    void shouldMapConfigFailuresToConfigNotFoundErrorCode() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        DefaultConversationEngine engine = new DefaultConversationEngine(
                sessionStore,
                context -> TurnDecision.builder()
                        .type(TurnDecision.Type.ACTION)
                        .actionPlan(ActionPlan.builder()
                                .sessionId(context.getSession().getSessionId())
                                .commandName("check")
                                .actionType(ActionType.EXPLAIN_CONFIG)
                                .build())
                        .build(),
                new DefaultClarificationManager(),
                new DefaultApprovalManager(),
                plan -> AiTaskResult.builder()
                        .success(false)
                        .taskType(AiTaskType.EXPLAIN)
                        .status(AiTurnResult.Status.FAILED)
                        .summary("No current config artifact found for explain.")
                        .suggestedNextAction("plan")
                        .build());

        ConversationResponse response = engine.handleUserTurn("session-4", "check");

        assertEquals(ConversationResponse.Type.ERROR, response.getType());
        assertEquals("config_not_found", response.getErrorCode());
    }

    @Test
    void shouldSupportPlannerAgentPathWithBridgeAndAssembler() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        DefaultConversationEngine engine = new DefaultConversationEngine(
                sessionStore,
                context -> com.consilens.ai.conversation.engine.model.PlannerResult.builder()
                        .type(com.consilens.ai.conversation.engine.model.PlannerType.PLAN)
                        .route(com.consilens.ai.conversation.engine.model.PlannerRoute.PLAN_CONFIG)
                        .normalizedGoal("compare users")
                        .slot("sourceType", "mysql")
                        .slot("targetType", "postgresql")
                        .slot("sourceTable", "users")
                        .slot("targetTable", "users")
                        .build(),
                new PlannerBridge(),
                new PlanContextAssembler(),
                new DefaultClarificationManager(),
                new DefaultApprovalManager(),
                plan -> AiTaskResult.builder()
                        .success(true)
                        .taskType(AiTaskType.PLAN_CONFIG)
                        .status(AiTurnResult.Status.COMPLETED)
                        .summary(plan.getCommandName())
                        .suggestedNextAction("run")
                        .build());

        ConversationResponse response = engine.handleUserTurn("session-planner", "compare users");

        assertEquals(ConversationResponse.Type.MESSAGE, response.getType());
        assertEquals("plan", response.getMessage());
    }

    private static class InMemorySessionStore implements AiSessionStore {

        private final Map<String, AiSession> sessions = new HashMap<>();

        @Override
        public Optional<AiSession> load(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public AiSession create(String preferredSessionId) {
            String sessionId = preferredSessionId == null ? "stub" : preferredSessionId;
            AiSession session = AiSession.builder()
                    .sessionId(sessionId)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .status("ready")
                    .currentTask("doctor")
                    .build();
            sessions.put(sessionId, session);
            return session;
        }

        @Override
        public void save(AiSession session) {
            sessions.put(session.getSessionId(), session);
        }

        @Override
        public List<AiSession> list() {
            return List.copyOf(sessions.values());
        }
    }
}
