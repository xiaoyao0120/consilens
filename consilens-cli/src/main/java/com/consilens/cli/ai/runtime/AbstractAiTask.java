package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class AbstractAiTask {

    protected final AiSessionStore sessionStore;
    protected final AiArtifactStore artifactStore;

    protected AbstractAiTask(AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        this.sessionStore = sessionStore;
        this.artifactStore = artifactStore;
    }

    protected ArtifactRef writeArtifact(String sessionId, ArtifactType type, String content, Map<String, String> metadata) {
        return artifactStore.write(sessionId, type, content.getBytes(StandardCharsets.UTF_8), metadata);
    }

    protected void writeOutput(String outputPath, String content) {
        if (outputPath == null || outputPath.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(outputPath).toAbsolutePath().normalize();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write output file " + outputPath, e);
        }
    }

    protected AiSession updateSession(AiSession session, java.util.function.Consumer<AiSession.AiSessionBuilder> mutator) {
        AiSession.AiSessionBuilder builder = session.toBuilder().updatedAt(Instant.now());
        mutator.accept(builder);
        AiSession updated = builder.build();
        sessionStore.save(updated);
        return updated;
    }

    protected Optional<ConfigGenerationRequest> configRequest(AiTaskContext context) {
        return Optional.ofNullable(context.attribute(AiRuntimeContextKeys.CONFIG_REQUEST, ConfigGenerationRequest.class));
    }

    protected String outputPath(AiTaskContext context) {
        return context.attribute(AiRuntimeContextKeys.OUTPUT_PATH, String.class);
    }

    protected boolean performDryRun(AiTaskContext context) {
        Boolean value = context.attribute(AiRuntimeContextKeys.PERFORM_DRY_RUN, Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    protected Optional<ConfigRef> loadCurrentConfig(AiSession session) {
        if (session.getCurrentConfigArtifactId() == null || session.getCurrentConfigArtifactId().isBlank()) {
            return Optional.empty();
        }
        return artifactStore.get(session.getCurrentConfigArtifactId())
                .flatMap(ref -> artifactStore.read(ref.getArtifactId())
                        .map(bytes -> ConfigRef.builder()
                                .sessionId(session.getSessionId())
                                .artifactId(ref.getArtifactId())
                                .path(ref.getPath())
                                .content(new String(bytes, StandardCharsets.UTF_8))
                                .build()));
    }

    protected String joinLines(List<String> lines) {
        return lines == null || lines.isEmpty() ? "" : String.join(System.lineSeparator(), lines);
    }

    protected AiTaskResult failure(com.consilens.ai.runtime.task.AiTaskType type, String message) {
        return AiTaskResult.builder()
                .success(false)
                .taskType(type)
                .status(AiTurnResult.Status.FAILED)
                .summary(message)
                .suggestedNextAction(type.name())
                .build();
    }
}
