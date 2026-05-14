package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.ExplainReport;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Explains the current config artifact in a session.
 */
public class ExplainTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;

    public ExplainTask(ConfigCapability configCapability, AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        this(configCapability, sessionStore, artifactStore, null);
    }

    public ExplainTask(ConfigCapability configCapability,
                       AiSessionStore sessionStore,
                       AiArtifactStore artifactStore,
                       AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
        this.configCapability = configCapability;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.EXPLAIN;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        ConfigRef configRef = resolveConfig(context);
        if (configRef == null) {
            return failure(type(), "No current config artifact found for explain.");
        }
        StringBuilder output = new StringBuilder();
        if (performDryRun(context)) {
            DryRunReport dryRun = configCapability.dryRun(configRef);
            if (!dryRun.isPassed()) {
                return failure(type(), "Dry run failed before explain: " + joinLines(dryRun.getMessages()));
            }
            output.append("Dry run:").append(System.lineSeparator())
                    .append(joinLines(dryRun.getMessages()))
                    .append(System.lineSeparator()).append(System.lineSeparator());
        }
        ExplainReport report = configCapability.explain(configRef);
        output.append(report.getMarkdown());
        writeOutput(outputPath(context), output.toString());
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary(inlineOutput(context) ? output.toString() : "Explained config for session " + context.getSession().getSessionId())
                .suggestedNextAction("run")
                .build();
    }

    private ConfigRef resolveConfig(AiTaskContext context) {
        String explicitPath = configPath(context);
        if (explicitPath == null || explicitPath.isBlank()) {
            return loadCurrentConfig(context.getSession()).orElse(null);
        }
        try {
            Path path = Path.of(explicitPath).toAbsolutePath().normalize();
            String content = Files.readString(path, StandardCharsets.UTF_8);
            ArtifactRef artifact = writeArtifact(
                    context.getSession().getSessionId(),
                    ArtifactType.CONFIG,
                    content,
                    Map.of("task", "explain", "sourcePath", path.toString()));
            updateSession(context.getSession(), builder -> builder.currentConfigArtifactId(artifact.getArtifactId()));
            return ConfigRef.builder()
                    .sessionId(context.getSession().getSessionId())
                    .artifactId(artifact.getArtifactId())
                    .path(path.toString())
                    .content(content)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load config for explain from " + explicitPath, e);
        }
    }
}
