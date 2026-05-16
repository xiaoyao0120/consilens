package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads an existing config file into the current session.
 */
public class UseConfigTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;

    public UseConfigTask(ConfigCapability configCapability,
                         AiSessionStore sessionStore,
                         AiArtifactStore artifactStore,
                         AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
        this.configCapability = configCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.USE_CONFIG;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        String configPath = configPath(context);
        if (configPath == null || configPath.isBlank()) {
            return failure(type(), "Config path is required for use-config.",
                    List.of(event("load-config", "failed", "Config path is required for use-config.")));
        }
        try {
            Path path = Path.of(configPath).toAbsolutePath().normalize();
            String content = Files.readString(path);
            ArtifactRef configArtifact = writeArtifact(
                    context.getSession().getSessionId(),
                    ArtifactType.CONFIG,
                    content,
                    Map.of("task", "use-config", "sourcePath", path.toString()));
            ValidationReport validation = configCapability.validate(ConfigRef.builder()
                    .sessionId(context.getSession().getSessionId())
                    .artifactId(configArtifact.getArtifactId())
                    .path(path.toString())
                    .content(content)
                    .build());
            ArtifactRef validationArtifact = writeArtifact(
                    context.getSession().getSessionId(),
                    ArtifactType.VALIDATION,
                    joinLines(validation.getMessages()),
                    Map.of("task", "use-config",
                            "sourcePath", path.toString(),
                            "configArtifactId", configArtifact.getArtifactId(),
                            "passed", String.valueOf(validation.isPassed())));
            updateSession(context.getSession(), builder -> builder
                    .currentTask("use-config")
                    .status(validation.isPassed() ? "config_loaded" : "needs_attention")
                    .currentConfigArtifactId(configArtifact.getArtifactId()));
            String summary = "Loaded config into session " + context.getSession().getSessionId()
                    + System.lineSeparator() + "config=" + configArtifact.getArtifactId()
                    + System.lineSeparator() + "validation=" + validationArtifact.getArtifactId()
                    + System.lineSeparator() + (validation.isPassed() ? "Validation passed" : joinLines(validation.getMessages()));
            return AiTaskResult.builder()
                    .success(validation.isPassed())
                    .taskType(type())
                    .status(validation.isPassed() ? AiTurnResult.Status.COMPLETED : AiTurnResult.Status.FAILED)
                    .summary(summary)
                    .suggestedNextAction(validation.isPassed() ? "run" : "repair")
                    .events(List.of(
                            event("load-config", "completed", "Loaded config file " + path, configArtifact),
                            event("validate", validation.isPassed() ? "completed" : "failed",
                                    joinLines(validation.getMessages()), validationArtifact)))
                    .build();
        } catch (Exception e) {
            return failure(type(), "Failed to load config: " + e.getMessage(),
                    List.of(event("load-config", "failed", "Failed to load config: " + e.getMessage())));
        }
    }
}
