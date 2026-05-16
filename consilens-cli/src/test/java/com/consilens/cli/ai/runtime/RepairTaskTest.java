package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.ExplainReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiMemoryCandidate;
import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairTaskTest {

    @Test
    void shouldRenderReadyForRetryRepairSummary() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        AiSession session = sessionStore.create("repair-1");
        ArtifactRef currentConfigArtifact = artifactStore.write(
                session.getSessionId(),
                ArtifactType.CONFIG,
                currentYaml().getBytes(StandardCharsets.UTF_8),
                Map.of("task", "plan", "goal", "compare orders"));
        ArtifactRef diagnosisArtifact = artifactStore.write(
                session.getSessionId(),
                ArtifactType.DIAGNOSIS,
                "Diagnosis: Add diff-record sink".getBytes(StandardCharsets.UTF_8),
                Map.of("task", "diagnose"));
        session = session.toBuilder()
                .currentConfigArtifactId(currentConfigArtifact.getArtifactId())
                .latestDiagnosisArtifactId(diagnosisArtifact.getArtifactId())
                .updatedAt(Instant.now())
                .build();
        sessionStore.save(session);

        RepairTask task = new RepairTask(new ConfigCapability() {
            @Override
            public GeneratedConfig generate(ConfigGenerationRequest request) {
                return GeneratedConfig.builder()
                        .configRef(ConfigRef.builder()
                                .sessionId(request.getSessionId())
                                .content(repairedYaml())
                                .build())
                        .build();
            }

            @Override
            public ValidationReport validate(ConfigRef configRef) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DryRunReport dryRun(ConfigRef configRef) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ExplainReport explain(ConfigRef configRef) {
                throw new UnsupportedOperationException();
            }
        }, sessionStore, artifactStore, new InMemoryMemoryStore());

        AiTaskResult result = task.execute(AiTaskContext.builder()
                .session(session)
                .build());

        assertEquals(AiTurnResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getSummary().contains("Repair status: READY_FOR_RETRY"));
        assertTrue(result.getSummary().contains("Changed Sections: result"));
        assertTrue(result.getSummary().contains("Next Action: RUN_AGAIN"));
        assertTrue(result.getSummary().contains("Before:"));
        assertTrue(result.getSummary().contains("After:"));
        assertNotNull(artifactStore.latest(session.getSessionId(), ArtifactType.REPAIR_PATCH).orElse(null));
        AiSession updated = sessionStore.load(session.getSessionId()).orElseThrow();
        assertEquals("repair_ready", updated.getStatus());
        assertNotNull(updated.getCurrentConfigArtifactId());
    }

    private String currentYaml() {
        return String.join(System.lineSeparator(),
                "source:",
                "  type: mysql",
                "  name: source-orders",
                "  connection:",
                "    url: jdbc:mysql://localhost:3306/source",
                "    username: ${env.SOURCE_USER}",
                "    password: ${env.SOURCE_PASSWORD}",
                "  resource:",
                "    type: table",
                "    name: orders",
                "target:",
                "  type: postgresql",
                "  name: target-orders",
                "  connection:",
                "    url: jdbc:postgresql://localhost:5432/target",
                "    username: ${env.TARGET_USER}",
                "    password: ${env.TARGET_PASSWORD}",
                "  resource:",
                "    type: table",
                "    name: orders",
                "comparison:",
                "  keys:",
                "    source: [id]",
                "    target: [id]",
                "strategy:",
                "  mode: checksum",
                "  algorithm: xor");
    }

    private String repairedYaml() {
        return currentYaml() + System.lineSeparator()
                + "result:" + System.lineSeparator()
                + "  sinks:" + System.lineSeparator()
                + "    - format: json" + System.lineSeparator()
                + "      type: diff-record";
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
            return new ArrayList<>(sessions.values());
        }
    }

    private static class InMemoryArtifactStore implements AiArtifactStore {
        private final Map<String, ArtifactRef> refs = new HashMap<>();
        private final Map<String, byte[]> contents = new HashMap<>();
        private int counter = 0;

        @Override
        public ArtifactRef write(String sessionId, ArtifactType type, byte[] content, Map<String, String> metadata) {
            String artifactId = "artifact-" + (++counter);
            ArtifactRef ref = ArtifactRef.builder()
                    .artifactId(artifactId)
                    .sessionId(sessionId)
                    .type(type)
                    .path("/tmp/" + artifactId)
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
                    .max(Comparator.comparing(ArtifactRef::getCreatedAt));
        }

        @Override
        public List<ArtifactRef> list(String sessionId) {
            List<ArtifactRef> result = new ArrayList<>();
            for (ArtifactRef ref : refs.values()) {
                if (sessionId.equals(ref.getSessionId())) {
                    result.add(ref);
                }
            }
            return result;
        }
    }

    private static class InMemoryMemoryStore implements AiMemoryStore {
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
    }
}
