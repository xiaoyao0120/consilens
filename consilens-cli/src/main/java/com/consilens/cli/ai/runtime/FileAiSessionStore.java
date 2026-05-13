package com.consilens.cli.ai.runtime;

import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiSession;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File-backed session store for the CLI runtime.
 */
public class FileAiSessionStore implements AiSessionStore {

    private final AiRuntimePaths paths;
    private final ObjectMapper mapper;

    public FileAiSessionStore(AiRuntimePaths paths) {
        this.paths = paths;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public synchronized Optional<AiSession> load(String sessionId) {
        Path path = sessionFile(sessionId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(path.toFile(), AiSession.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load AI session " + sessionId, e);
        }
    }

    @Override
    public synchronized AiSession create(String preferredSessionId) {
        Instant now = Instant.now();
        String sessionId = preferredSessionId == null || preferredSessionId.trim().isEmpty()
                ? "ai-" + UUID.randomUUID()
                : preferredSessionId.trim();
        AiSession session = AiSession.builder()
                .sessionId(sessionId)
                .createdAt(now)
                .updatedAt(now)
                .status("ready")
                .currentTask("doctor")
                .build();
        save(session);
        return session;
    }

    @Override
    public synchronized void save(AiSession session) {
        try {
            Files.createDirectories(paths.sessionsDir());
            mapper.writeValue(sessionFile(session.getSessionId()).toFile(), session);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save AI session " + session.getSessionId(), e);
        }
    }

    @Override
    public synchronized List<AiSession> list() {
        if (!Files.exists(paths.sessionsDir())) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(paths.sessionsDir())) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            return mapper.readValue(path.toFile(), AiSession.class);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to read AI session file " + path, e);
                        }
                    })
                    .sorted(Comparator.comparing(AiSession::getUpdatedAt).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list AI sessions", e);
        }
    }

    private Path sessionFile(String sessionId) {
        return paths.sessionsDir().resolve(sessionId + ".json");
    }
}
