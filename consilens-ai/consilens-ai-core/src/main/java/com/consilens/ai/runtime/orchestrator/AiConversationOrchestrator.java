package com.consilens.ai.runtime.orchestrator;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.intent.AiIntent;
import com.consilens.ai.runtime.intent.CompareIntentHintExtractor;
import com.consilens.ai.runtime.intent.IntentRouter;
import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.runtime.task.TaskRegistry;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;

import java.util.Optional;

/**
 * Default orchestrator skeleton for the refactored AI-first runtime.
 */
public class AiConversationOrchestrator implements AiConversationRuntime {

    private static final String CONFIG_REQUEST_KEY = "configRequest";

    private final IntentRouter intentRouter;
    private final TaskRegistry taskRegistry;
    private final AiSessionStore sessionStore;

    public AiConversationOrchestrator(IntentRouter intentRouter,
                                      TaskRegistry taskRegistry,
                                      AiSessionStore sessionStore) {
        this.intentRouter = intentRouter;
        this.taskRegistry = taskRegistry;
        this.sessionStore = sessionStore;
    }

    @Override
    public AiTurnResult handleUserInput(String sessionId, String input) {
        AiSession session = loadOrCreate(sessionId);
        AiIntent intent = intentRouter.route(session, input);
        AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                .session(session)
                .userInput(input);
        if (input != null && !input.isBlank()) {
            if (intent == AiIntent.PLAN_CONFIG || intent == AiIntent.MODIFY_CONFIG || intent == AiIntent.RUN_DIFF) {
                builder.attribute(CONFIG_REQUEST_KEY,
                        CompareIntentHintExtractor.enrich(session.getSessionId(), input.trim(), null));
            }
        }
        return dispatch(map(intent), builder.build());
    }

    @Override
    public AiTurnResult executeCommand(String sessionId, AiTaskContext context) {
        AiSession session = loadOrCreate(sessionId);
        AiTaskContext taskContext = context == null
                ? AiTaskContext.builder().session(session).build()
                : context.toBuilder().session(session).build();
        if (taskContext.getCommand() == null || taskContext.getCommand().getName() == null) {
            return AiTurnResult.builder()
                    .status(AiTurnResult.Status.FAILED)
                    .message("Command name is required")
                    .build();
        }
        return dispatch(map(taskContext.getCommand().getName()), taskContext);
    }

    @Override
    public AiSessionSnapshot snapshot(String sessionId) {
        AiSession session = loadOrCreate(sessionId);
        return AiSessionSnapshot.builder()
                .session(session)
                .latestArtifactId(session.getLatestRunArtifactId())
                .build();
    }

    private AiSession loadOrCreate(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return sessionStore.create();
        }
        Optional<AiSession> existing = sessionStore.load(sessionId);
        return existing.orElseGet(() -> sessionStore.create(sessionId));
    }

    private AiTaskType map(AiIntent intent) {
        switch (intent) {
            case PLAN_CONFIG:
            case MODIFY_CONFIG:
                return AiTaskType.PLAN_CONFIG;
            case EXPLAIN_CONFIG:
                return AiTaskType.EXPLAIN;
            case RUN_DIFF:
                return AiTaskType.RUN_DIFF;
            case DIAGNOSE_RESULT:
                return AiTaskType.DIAGNOSE;
            case REPAIR_CONFIG:
                return AiTaskType.REPAIR;
            case GENERAL_QA:
            case NEED_CLARIFICATION:
            default:
                return AiTaskType.DOCTOR;
        }
    }

    private AiTaskType map(String commandName) {
        if (commandName == null) {
            return AiTaskType.DOCTOR;
        }
        switch (commandName.trim().toLowerCase()) {
            case "plan":
                return AiTaskType.PLAN_CONFIG;
            case "validate":
                return AiTaskType.VALIDATE;
            case "check":
            case "dry-run":
                return AiTaskType.DRY_RUN;
            case "run":
            case "diff":
                return AiTaskType.RUN_DIFF;
            case "diagnose":
            case "analyze-last":
                return AiTaskType.DIAGNOSE;
            case "repair":
                return AiTaskType.REPAIR;
            case "remember":
                return AiTaskType.MEMORY_ADD;
            case "forget":
                return AiTaskType.MEMORY_REMOVE;
            case "explain":
                return AiTaskType.EXPLAIN;
            case "doctor":
            default:
                return AiTaskType.DOCTOR;
        }
    }

    private AiTurnResult dispatch(AiTaskType taskType, AiTaskContext context) {
        return taskRegistry.get(taskType)
                .map(task -> toTurnResult(task.execute(context), taskType))
                .orElseGet(() -> AiTurnResult.builder()
                        .status(AiTurnResult.Status.FAILED)
                        .message("No task registered for " + taskType)
                        .suggestedTask(taskType.name())
                        .build());
    }

    private AiTurnResult toTurnResult(AiTaskResult result, AiTaskType taskType) {
        if (result == null) {
            return AiTurnResult.builder()
                    .status(AiTurnResult.Status.FAILED)
                    .message("Task returned no result: " + taskType)
                    .suggestedTask(taskType.name())
                    .build();
        }
        return AiTurnResult.builder()
                .status(result.getStatus() == null
                        ? (result.isSuccess() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                        : result.getStatus())
                .message(result.getSummary())
                .suggestedTask(result.getSuggestedNextAction() == null
                        ? taskType.name()
                        : result.getSuggestedNextAction())
                .build();
    }
}
