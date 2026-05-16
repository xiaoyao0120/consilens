package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigRef;
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
import java.util.List;
import java.util.Map;

/**
 * Validates current (or explicit) config and stores validation report artifact.
 */
public class ValidateConfigTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;

    public ValidateConfigTask(ConfigCapability configCapability,
                              AiSessionStore sessionStore,
                              AiArtifactStore artifactStore,
                              AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
        this.configCapability = configCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.VALIDATE;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        ConfigRef configRef = resolveConfig(context);
        if (configRef == null) {
            return failure(type(), "No current config artifact found for validate.",
                    List.of(event("validate", "failed", "No current config artifact found for validate.")));
        }
        ValidationReport validation = configCapability.validate(configRef);
        ArtifactType artifactType = ArtifactType.VALIDATION;
        String configArtifactId = configRef.getArtifactId() == null ? "" : configRef.getArtifactId();
        String configPath = configRef.getPath() == null ? "" : configRef.getPath();
        com.consilens.ai.session.model.ArtifactRef validationArtifact = writeArtifact(context.getSession().getSessionId(),
                ArtifactType.VALIDATION,
                joinLines(validation.getMessages()),
                Map.of("task", "validate", "passed", String.valueOf(validation.isPassed()),
                        "configArtifactId", configArtifactId,
                        "configPath", configPath));

        updateSession(context.getSession(), builder -> builder
                .currentTask("validate")
                .status(validation.isPassed() ? "validated" : "needs_attention"));

        String summary = "Validation " + (validation.isPassed() ? "passed" : "failed")
                + " for session " + context.getSession().getSessionId();
        String messages = joinLines(validation.getMessages());
        if (!validation.getMessages().isEmpty()) {
            summary += System.lineSeparator() + messages;
        }
        return AiTaskResult.builder()
                .success(validation.isPassed())
                .taskType(type())
                .status(validation.isPassed() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                .summary(inlineOutput(context) && !messages.isBlank() ? messages : summary)
                .suggestedNextAction(validation.isPassed() ? "dry-run" : "repair")
                .events(List.of(event("validate", validation.isPassed() ? "completed" : "failed",
                        messages.isBlank() ? summary : messages, validationArtifact)))
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
