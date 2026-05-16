package com.consilens.ai.conversation.service;

import com.consilens.ai.conversation.api.model.SaveConfigResponse;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.engine.ConversationEngine;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiMemoryCandidate;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultConversationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStartNewSessionByDefaultWhenPreferredSessionIsMissing() {
        TrackingSessionStore sessionStore = new TrackingSessionStore();
        sessionStore.create("existing");
        DefaultConversationService service = new DefaultConversationService(
                noopEngine(), sessionStore, new InMemoryArtifactStore(), noopMemoryStore());

        String sessionId = service.startSession(null, false).getSessionId();

        assertEquals("auto-1", sessionId);
    }

    @Test
    void shouldSaveCurrentConfigToPath() throws Exception {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        String sessionId = "s1";
        ArtifactRef ref = artifactStore.write(sessionId, ArtifactType.CONFIG,
                "key: value".getBytes(StandardCharsets.UTF_8), Map.of());
        sessionStore.save(AiSession.builder()
                .sessionId(sessionId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("ready")
                .currentTask("plan")
                .currentConfigArtifactId(ref.getArtifactId())
                .build());

        DefaultConversationService service = new DefaultConversationService(
                noopEngine(), sessionStore, artifactStore, noopMemoryStore());
        Path output = tempDir.resolve("config.yaml");

        SaveConfigResponse response = service.saveCurrentConfig(sessionId, output.toString());

        assertTrue(response.isSaved());
        assertEquals(ref.getArtifactId(), response.getArtifactId());
        assertTrue(Files.exists(output));
        assertEquals("key: value", Files.readString(output));
    }

    @Test
    void shouldReturnNotSavedWhenNoCurrentConfig() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        DefaultConversationService service = new DefaultConversationService(
                noopEngine(), sessionStore, new InMemoryArtifactStore(), noopMemoryStore());

        SaveConfigResponse response = service.saveCurrentConfig("missing-session", tempDir.resolve("x.yaml").toString());

        assertFalse(response.isSaved());
        assertTrue(response.getMessage().contains("No current config"));
    }

    @Test
    void shouldExposeArtifactMetadataAndContent() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        String sessionId = "s-artifact";
        ArtifactRef ref = artifactStore.write(sessionId, ArtifactType.RUN_AUDIT,
                "audit body".getBytes(StandardCharsets.UTF_8),
                Map.of("resultArtifactId", "result-1"));

        DefaultConversationService service = new DefaultConversationService(
                noopEngine(), sessionStore, artifactStore, noopMemoryStore());

        List<ArtifactEntryDto> artifacts = service.listArtifacts(sessionId, 10);
        ArtifactContentResponse artifact = service.getArtifact(ref.getArtifactId());

        assertEquals(1, artifacts.size());
        assertEquals("RUN_AUDIT", artifacts.get(0).getType());
        assertEquals("sha-" + ref.getArtifactId(), artifacts.get(0).getSha256());
        assertEquals("result-1", artifact.getMetadata().get("resultArtifactId"));
        assertEquals("audit body", artifact.getContent());
        assertEquals("/tmp/" + ref.getArtifactId(), artifact.getPath());
    }

    @Test
    void shouldRecommendValidateAndShowArtifactPathsDuringRecovery() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        String sessionId = "s-recovery";
        ArtifactRef config = artifactStore.write(sessionId, ArtifactType.CONFIG,
                "key: value".getBytes(StandardCharsets.UTF_8), Map.of());
        sessionStore.save(AiSession.builder()
                .sessionId(sessionId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .status("needs_attention")
                .currentTask("use-config")
                .currentConfigArtifactId(config.getArtifactId())
                .build());

        DefaultConversationService service = new DefaultConversationService(
                noopEngine(), sessionStore, artifactStore, noopMemoryStore());

        com.consilens.ai.conversation.api.model.SessionRecoveryResponse response = service.recoverSession(sessionId);

        assertEquals("validate", response.getRecommendedAction());
        assertTrue(response.getSummary().contains(config.getArtifactId() + " @ " + config.getPath()));
    }

    private ConversationEngine noopEngine() {
        return new ConversationEngine() {
            @Override
            public com.consilens.ai.conversation.api.model.ConversationResponse handleUserTurn(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.consilens.ai.conversation.api.model.ConversationResponse executeCommand(
                    com.consilens.ai.conversation.api.model.ConversationCommandRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.consilens.ai.conversation.api.model.ConversationResponse approve(String sessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.consilens.ai.conversation.api.model.ConversationResponse deny(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private AiMemoryStore noopMemoryStore() {
        return new AiMemoryStore() {
            @Override
            public List<AiMemory> list() {
                return List.of();
            }

            @Override
            public Optional<AiMemory> add(AiMemoryCandidate candidate) {
                return Optional.empty();
            }

            @Override
            public void remove(String memoryId) {
            }
        };
    }

    private static class InMemorySessionStore implements AiSessionStore {
        private final Map<String, AiSession> sessions = new HashMap<>();

        @Override
        public Optional<AiSession> load(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public AiSession create(String preferredSessionId) {
            String id = preferredSessionId == null ? "default" : preferredSessionId;
            AiSession session = AiSession.builder()
                    .sessionId(id)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .status("ready")
                    .currentTask("doctor")
                    .build();
            sessions.put(id, session);
            return session;
        }

        @Override
        public void save(AiSession session) {
            sessions.put(session.getSessionId(), session);
        }

        @Override
        public List<AiSession> list() {
            return List.copyOf(sessions.values());
        }
    }

    private static class TrackingSessionStore implements AiSessionStore {
        private final List<AiSession> sessions = new ArrayList<>();
        private int sequence = 0;

        @Override
        public Optional<AiSession> load(String sessionId) {
            return sessions.stream().filter(session -> session.getSessionId().equals(sessionId)).findFirst();
        }

        @Override
        public AiSession create(String preferredSessionId) {
            String id = preferredSessionId == null ? "auto-" + (++sequence) : preferredSessionId;
            AiSession session = AiSession.builder()
                    .sessionId(id)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .status("ready")
                    .currentTask("doctor")
                    .build();
            sessions.add(session);
            return session;
        }

        @Override
        public void save(AiSession session) {
            sessions.removeIf(existing -> existing.getSessionId().equals(session.getSessionId()));
            sessions.add(session);
        }

        @Override
        public List<AiSession> list() {
            return List.copyOf(sessions);
        }
    }

    private static class InMemoryArtifactStore implements AiArtifactStore {
        private final Map<String, ArtifactRef> refs = new HashMap<>();
        private final Map<String, byte[]> contents = new HashMap<>();

        @Override
        public ArtifactRef write(String sessionId, ArtifactType type, byte[] content, Map<String, String> metadata) {
            String artifactId = "artifact-" + (refs.size() + 1);
            ArtifactRef ref = ArtifactRef.builder()
                    .artifactId(artifactId)
                    .sessionId(sessionId)
                    .type(type)
                    .path("/tmp/" + artifactId)
                    .sha256("sha-" + artifactId)
                    .metadata(metadata)
                    .createdAt(Instant.now())
                    .build();
            refs.put(artifactId, ref);
            contents.put(artifactId, content);
            return ref;
        }

        @Override
        public Optional<ArtifactRef> get(String artifactId) {
            return Optional.ofNullable(refs.get(artifactId));
        }

        @Override
        public Optional<byte[]> read(String artifactId) {
            return Optional.ofNullable(contents.get(artifactId));
        }

        @Override
        public Optional<ArtifactRef> latest(String sessionId, ArtifactType type) {
            return refs.values().stream()
                    .filter(ref -> sessionId.equals(ref.getSessionId()) && type == ref.getType())
                    .findFirst();
        }

        @Override
        public List<ArtifactRef> list(String sessionId) {
            return refs.values().stream()
                    .filter(ref -> sessionId.equals(ref.getSessionId()))
                    .collect(Collectors.toList());
        }
    }
}
