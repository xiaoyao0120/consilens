package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiConsoleCommand;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.runtime.task.TaskRegistry;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;

import java.util.Map;

/**
 * Executes conversation actions by delegating to existing runtime tasks.
 */
public class TaskActionExecutor implements ActionExecutor {

    private static final String PERFORM_DRY_RUN_KEY = "performDryRun";
    private static final String INLINE_OUTPUT_KEY = "inlineOutput";

    private final TaskRegistry taskRegistry;
    private final AiSessionStore sessionStore;

    public TaskActionExecutor(TaskRegistry taskRegistry, AiSessionStore sessionStore) {
        this.taskRegistry = taskRegistry;
        this.sessionStore = sessionStore;
    }

    @Override
    public AiTaskResult execute(ActionPlan plan) {
        AiSession session = sessionStore.load(plan.getSessionId())
                .orElseGet(() -> sessionStore.create(plan.getSessionId()));
        AiTaskType taskType = map(plan.getCommandName());
        try {
            AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                    .session(session)
                    .userInput(plan.getUserInput())
                    .command(AiConsoleCommand.builder()
                            .name(plan.getCommandName())
                            .argument(plan.getCommandArgument())
                            .build());
            Map<String, Object> attributes = plan.getAttributes();
            if (attributes != null) {
                attributes.forEach(builder::attribute);
            }
            if (("run".equalsIgnoreCase(plan.getCommandName()) || "diff".equalsIgnoreCase(plan.getCommandName()))
                    && plan.isRequiresApproval()) {
                builder.attribute("approveExecute", true)
                        .attribute("approvalMode", ApprovalMode.EXPLICIT_FLAG);
            }
            if ("check".equalsIgnoreCase(plan.getCommandName())) {
                builder.attribute(PERFORM_DRY_RUN_KEY, true);
                builder.attribute(INLINE_OUTPUT_KEY, true);
            }
            return taskRegistry.get(taskType)
                    .map(task -> task.execute(builder.build()))
                    .orElseGet(() -> AiTaskResult.builder()
                            .success(false)
                            .taskType(taskType)
                            .status(AiTurnResult.Status.FAILED)
                            .summary("No task registered for " + taskType)
                            .suggestedNextAction("doctor")
                            .build());
        } catch (RuntimeException e) {
            return AiTaskResult.builder()
                    .success(false)
                    .taskType(taskType)
                    .status(AiTurnResult.Status.FAILED)
                    .summary("Action execution failed: " + e.getMessage())
                    .suggestedNextAction("doctor")
                    .build();
        }
    }

    private AiTaskType map(String commandName) {
        if (commandName == null) {
            return AiTaskType.DOCTOR;
        }
        switch (commandName.trim().toLowerCase()) {
            case "use-config":
                return AiTaskType.USE_CONFIG;
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
}
