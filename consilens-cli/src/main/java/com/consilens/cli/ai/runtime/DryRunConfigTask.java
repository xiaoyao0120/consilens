package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskEvent;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Performs dry-run preflight on current (or explicit) config.
 */
public class DryRunConfigTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;

    public DryRunConfigTask(ConfigCapability configCapability,
                            AiSessionStore sessionStore,
                            AiArtifactStore artifactStore,
                            AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
        this.configCapability = configCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.DRY_RUN;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        ConfigRef configRef = resolveConfig(context);
        if (configRef == null) {
            return failure(type(), "No current config artifact found for dry-run.",
                    List.of(event("dry-run", "failed", "No current config artifact found for dry-run.")));
        }
        List<AiTaskEvent> events = new ArrayList<>();
        ValidationReport validation = configCapability.validate(configRef);
        String configArtifactId = configRef.getArtifactId() == null ? "" : configRef.getArtifactId();
        String configPath = configRef.getPath() == null ? "" : configRef.getPath();
        com.consilens.ai.session.model.ArtifactRef validationArtifact = writeArtifact(context.getSession().getSessionId(),
                ArtifactType.VALIDATION,
                joinLines(validation.getMessages()),
                Map.of("task", "dry-run", "passed", String.valueOf(validation.isPassed()),
                        "configArtifactId", configArtifactId,
                        "configPath", configPath));
        events.add(event("validate", validation.isPassed() ? "completed" : "failed",
                joinLines(validation.getMessages()), validationArtifact));
        if (!validation.isPassed()) {
            return failure(type(), "Config validation failed before dry run: " + joinLines(validation.getMessages()), events);
        }

        DryRunReport dryRun = configCapability.dryRun(configRef);
        com.consilens.ai.session.model.ArtifactRef dryRunArtifact = writeArtifact(context.getSession().getSessionId(),
                ArtifactType.DRY_RUN,
                joinLines(dryRun.getMessages()),
                Map.of("task", "dry-run", "passed", String.valueOf(dryRun.isPassed()),
                        "configArtifactId", configArtifactId,
                        "validationArtifactId", validationArtifact.getArtifactId(),
                        "configPath", configPath));
        events.add(event("dry-run", dryRun.isPassed() ? "completed" : "failed",
                joinLines(dryRun.getMessages()), dryRunArtifact));

        updateSession(context.getSession(), builder -> builder
                .currentTask("dry-run")
                .status(dryRun.isPassed() ? "dry_run_passed" : "needs_attention"));

        String summary = "Dry run " + (dryRun.isPassed() ? "passed" : "failed")
                + " for session " + context.getSession().getSessionId();
        String messages = joinLines(dryRun.getMessages());
        if (!dryRun.getMessages().isEmpty()) {
            summary += System.lineSeparator() + messages;
        }
        return AiTaskResult.builder()
                .success(dryRun.isPassed())
                .taskType(type())
                .status(dryRun.isPassed() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                .summary(inlineOutput(context) && !messages.isBlank() ? messages : summary)
                .suggestedNextAction(dryRun.isPassed() ? "run" : "repair")
                .events(events)
                .build();
    }

    private ConfigRef resolveConfig(AiTaskContext context) {
        String explicitPath = configPath(context);
        if (explicitPath != null && !explicitPath.isBlank()) {
            try {
                Path path = Path.of(explicitPath).toAbsolutePath().normalize();
                String content = Files.readString(path);
                return ConfigRef.builder()
                        .sessionId(context.getSession().getSessionId())
                        .path(path.toString())
                        .content(content)
                        .build();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read config from " + explicitPath, e);
            }
        }
        AiSession session = context.getSession();
        return loadCurrentConfig(session).orElse(null);
    }
}
