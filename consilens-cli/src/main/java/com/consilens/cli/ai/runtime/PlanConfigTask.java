package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generates and validates a canonical config artifact for a session.
 */
public class PlanConfigTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;

    public PlanConfigTask(ConfigCapability configCapability, AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        super(sessionStore, artifactStore);
        this.configCapability = configCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.PLAN_CONFIG;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        AiSession session = context.getSession();
        ConfigGenerationRequest request = configRequest(context)
                .orElse(null);
        if (request == null) {
            return failure(type(), "Config generation input is required for plan.");
        }

        GeneratedConfig generated = configCapability.generate(request);
        ArtifactRef configArtifact = writeArtifact(
                session.getSessionId(),
                ArtifactType.CONFIG,
                generated.getConfigRef().getContent(),
                Map.of("task", "plan", "goal", request.getGoal() == null ? "" : request.getGoal()));

        ConfigRef configRef = ConfigRef.builder()
                .sessionId(session.getSessionId())
                .artifactId(configArtifact.getArtifactId())
                .path(configArtifact.getPath())
                .content(generated.getConfigRef().getContent())
                .build();

        ValidationReport validation = configCapability.validate(configRef);
        writeArtifact(session.getSessionId(), ArtifactType.VALIDATION, joinLines(validation.getMessages()),
                Map.of("task", "plan", "passed", String.valueOf(validation.isPassed())));

        DryRunReport dryRun = null;
        if (performDryRun(context)) {
            dryRun = configCapability.dryRun(configRef);
            writeArtifact(session.getSessionId(), ArtifactType.DRY_RUN, joinLines(dryRun.getMessages()),
                    Map.of("task", "plan", "passed", String.valueOf(dryRun.isPassed())));
        }

        writeOutput(outputPath(context), generated.getConfigRef().getContent());

        boolean success = validation.isPassed() && (dryRun == null || dryRun.isPassed());

        updateSession(session, builder -> builder
                .currentTask("plan")
                .status(success ? "planned" : "needs_attention")
                .title(request.getGoal())
                .currentConfigArtifactId(configArtifact.getArtifactId()));
        StringBuilder summary = new StringBuilder()
                .append("Planned config for session ").append(session.getSessionId())
                .append(" config=").append(configArtifact.getArtifactId());
        if (!validation.getMessages().isEmpty()) {
            summary.append(System.lineSeparator()).append("Validation: ").append(joinLines(validation.getMessages()));
        }
        if (dryRun != null && !dryRun.getMessages().isEmpty()) {
            summary.append(System.lineSeparator()).append("Dry run: ").append(joinLines(dryRun.getMessages()));
        }
        return AiTaskResult.builder()
                .success(success)
                .taskType(type())
                .status(success ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                .summary(summary.toString())
                .suggestedNextAction(success ? "run" : "plan")
                .build();
    }
}
