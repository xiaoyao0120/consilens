package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.DiagnoseCapability;
import com.consilens.ai.execution.DiffCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DiagnoseReport;
import com.consilens.ai.execution.model.DiffExecutionReport;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.EvidenceRef;
import com.consilens.ai.execution.model.ExplainReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.approval.ExecutionApprovalService;
import com.consilens.ai.runtime.model.AiConsoleCommand;
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

class RunDiffTaskTest {

    @Test
    void shouldRenderDetailedApprovalPrompt() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        AiSession session = sessionStore.create("run-approval");
        ArtifactRef configArtifact = artifactStore.write(
                session.getSessionId(),
                ArtifactType.CONFIG,
                sampleYaml().getBytes(StandardCharsets.UTF_8),
                Map.of("task", "plan"));
        session = session.toBuilder()
                .currentConfigArtifactId(configArtifact.getArtifactId())
                .updatedAt(Instant.now())
                .build();
        sessionStore.save(session);

        RunDiffTask task = new RunDiffTask(
                passedConfigCapability(),
                new DiffCapability() {
                    @Override
                    public DiffExecutionReport execute(ConfigRef configRef) {
                        throw new IllegalStateException("diff should not execute before approval");
                    }

                    @Override
                    public Optional<com.consilens.ai.execution.model.LatestDiffPointer> latest(String sessionId) {
                        return Optional.empty();
                    }
                },
                evidenceRef -> {
                    throw new IllegalStateException("diagnose should not execute before approval");
                },
                (aiSession, action, mode, approvalText) -> false,
                sessionStore,
                artifactStore,
                new InMemoryMemoryStore());

        AiTaskResult result = task.execute(AiTaskContext.builder()
                .session(session)
                .command(AiConsoleCommand.builder().name("run").build())
                .attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.EXPLICIT_FLAG)
                .build());

        assertEquals(AiTurnResult.Status.REQUIRES_APPROVAL, result.getStatus());
        assertTrue(result.getSummary().contains("About to execute diff:"));
        assertTrue(result.getSummary().contains("source: mysql source-orders table:orders"));
        assertTrue(result.getSummary().contains("target: postgresql target-orders table:orders"));
        assertTrue(result.getSummary().contains("strategy: checksum/xor"));
        assertTrue(result.getSummary().contains("result sinks: console/result, json/diff-record"));
        assertTrue(result.getSummary().contains("Type: /approve execute"));
        assertNotNull(artifactStore.latest(session.getSessionId(), ArtifactType.VALIDATION).orElse(null));
        assertNotNull(artifactStore.latest(session.getSessionId(), ArtifactType.DRY_RUN).orElse(null));
    }

    @Test
    void shouldPersistRunArtifactsAndRenderStageSummary() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemoryArtifactStore artifactStore = new InMemoryArtifactStore();
        AiSession session = sessionStore.create("run-complete");
        ArtifactRef configArtifact = artifactStore.write(
                session.getSessionId(),
                ArtifactType.CONFIG,
                sampleYaml().getBytes(StandardCharsets.UTF_8),
                Map.of("task", "plan"));
        session = session.toBuilder()
                .currentConfigArtifactId(configArtifact.getArtifactId())
                .updatedAt(Instant.now())
                .build();
        sessionStore.save(session);

        RunDiffTask task = new RunDiffTask(
                passedConfigCapability(),
                new DiffCapability() {
                    @Override
                    public DiffExecutionReport execute(ConfigRef configRef) {
                        return DiffExecutionReport.builder()
                                .sessionId(configRef.getSessionId())
                                .runId("run-001")
                                .success(true)
                                .summary("3 differences found")
                                .resultArtifactId("result-001")
                                .evidenceRef(EvidenceRef.builder()
                                        .sessionId(configRef.getSessionId())
                                        .artifactId("evidence-001")
                                        .path("/tmp/evidence-001.json")
                                        .build())
                                .build();
                    }

                    @Override
                    public Optional<com.consilens.ai.execution.model.LatestDiffPointer> latest(String sessionId) {
                        return Optional.empty();
                    }
                },
                evidenceRef -> DiagnoseReport.builder()
                        .summary("Detected precision drift")
                        .repairHint("Review normalization rules")
                        .repairHint("Retry with repaired config")
                        .build(),
                (aiSession, action, mode, approvalText) -> true,
                sessionStore,
                artifactStore,
                new InMemoryMemoryStore());

        AiTaskResult result = task.execute(AiTaskContext.builder()
                .session(session)
                .command(AiConsoleCommand.builder().name("diff").build())
                .attribute(AiRuntimeContextKeys.APPROVE_EXECUTE, true)
                .attribute(AiRuntimeContextKeys.APPROVAL_MODE, ApprovalMode.EXPLICIT_FLAG)
                .build());

        assertEquals(AiTurnResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getSummary().contains("Execution:"));
        assertTrue(result.getSummary().contains("Stages:"));
        assertTrue(result.getSummary().contains("Artifacts:"));
        assertTrue(result.getSummary().contains("validate: passed"));
        assertTrue(result.getSummary().contains("dry-run: passed"));
        assertTrue(result.getSummary().contains("diff: completed"));
        assertTrue(result.getSummary().contains("Diagnosis: Detected precision drift"));
        assertNotNull(artifactStore.latest(session.getSessionId(), ArtifactType.APPROVAL).orElse(null));
        assertNotNull(artifactStore.latest(session.getSessionId(), ArtifactType.DIAGNOSIS).orElse(null));
        assertNotNull(artifactStore.latest(session.getSessionId(), ArtifactType.RUN_AUDIT).orElse(null));
        AiSession updated = sessionStore.load(session.getSessionId()).orElseThrow();
        assertEquals("result-001", updated.getLatestRunArtifactId());
        assertNotNull(updated.getLatestDiagnosisArtifactId());
        assertNotNull(updated.getLatestAuditArtifactId());
    }

    private ConfigCapability passedConfigCapability() {
        return new ConfigCapability() {
            @Override
            public GeneratedConfig generate(ConfigGenerationRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ValidationReport validate(ConfigRef configRef) {
                return ValidationReport.builder().passed(true).message("validation passed").build();
            }

            @Override
            public DryRunReport dryRun(ConfigRef configRef) {
                return DryRunReport.builder().passed(true).message("dry run passed").build();
            }

            @Override
            public ExplainReport explain(ConfigRef configRef) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private String sampleYaml() {
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
                "  algorithm: xor",
                "  localCompare:",
                "    mode: full",
                "result:",
                "  sinks:",
                "    - format: console",
                "      type: result",
                "    - format: json",
                "      type: diff-record");
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
