package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiMemoryCandidate;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

abstract class AbstractAiTask {

    protected final AiSessionStore sessionStore;
    protected final AiArtifactStore artifactStore;
    protected final AiMemoryStore memoryStore;

    protected AbstractAiTask(AiSessionStore sessionStore, AiArtifactStore artifactStore, AiMemoryStore memoryStore) {
        this.sessionStore = sessionStore;
        this.artifactStore = artifactStore;
        this.memoryStore = memoryStore;
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

    protected boolean inlineOutput(AiTaskContext context) {
        Boolean value = context.attribute(AiRuntimeContextKeys.INLINE_OUTPUT, Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    protected String configPath(AiTaskContext context) {
        return context.attribute(AiRuntimeContextKeys.CONFIG_PATH, String.class);
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

    protected void remember(String type, String content, String source) {
        if (memoryStore == null || content == null || content.trim().isEmpty()) {
            return;
        }
        memoryStore.add(AiMemoryCandidate.builder()
                .type(type)
                .content(content)
                .source(source)
                .build());
    }

    protected ConfigGenerationRequest enrichWithMemories(ConfigGenerationRequest request, String sessionId) {
        if (request == null || memoryStore == null) {
            return request;
        }
        List<String> relevantMemories = relevantMemories(sessionId, request.getGoal(), 4);
        if (relevantMemories.isEmpty()) {
            return request;
        }
        ConfigGenerationRequest.ConfigGenerationRequestBuilder builder = ConfigGenerationRequest.builder()
                .sessionId(request.getSessionId())
                .goal((request.getGoal() == null ? "" : request.getGoal())
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "Relevant runtime memory:"
                        + System.lineSeparator()
                        + relevantMemories.stream()
                                .map(memory -> "- " + memory)
                                .collect(Collectors.joining(System.lineSeparator())));
        if (request.getHints() != null) {
            request.getHints().forEach(builder::hint);
        }
        return builder.build();
    }

    private List<String> relevantMemories(String sessionId, String query, int limit) {
        List<com.consilens.ai.session.model.AiMemory> memories = memoryStore.list();
        if (memories.isEmpty()) {
            return List.of();
        }
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        for (String token : normalizedQuery.split("[^a-z0-9_]+")) {
            if (!token.isBlank() && token.length() > 2) {
                terms.add(token);
            }
        }
        return memories.stream()
                .filter(memory -> memory.getSource() == null
                        || memory.getSource().contains(sessionId)
                        || terms.stream().anyMatch(term -> memory.getContent().toLowerCase(Locale.ROOT).contains(term)))
                .sorted((left, right) -> memoryCreatedAt(right).compareTo(memoryCreatedAt(left)))
                .limit(limit)
                .map(memory -> "[" + memory.getType() + "] " + memory.getContent())
                .collect(Collectors.toList());
    }

    private Instant memoryCreatedAt(com.consilens.ai.session.model.AiMemory memory) {
        return memory == null || memory.getCreatedAt() == null ? Instant.EPOCH : memory.getCreatedAt();
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
