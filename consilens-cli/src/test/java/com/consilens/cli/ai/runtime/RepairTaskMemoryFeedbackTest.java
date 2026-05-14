package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.ExplainReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.model.AiTaskContext;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairTaskMemoryFeedbackTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldUseMemoryForGenerationWithoutPersistingInjectedPrompt() throws Exception {
        AiRuntimePaths paths = new AiRuntimePaths(tempDir.toString());
        FileAiSessionStore sessionStore = new FileAiSessionStore(paths);
        FileAiArtifactStore artifactStore = new FileAiArtifactStore(paths);
        FileAiMemoryStore memoryStore = new FileAiMemoryStore(paths);
        AiSession session = sessionStore.create("repair-session");
        ArtifactRef configArtifact = artifactStore.write(
                session.getSessionId(),
                ArtifactType.CONFIG,
                minimalConfig().getBytes(StandardCharsets.UTF_8),
                Map.of("goal", "Compare users"));
        artifactStore.write(
                session.getSessionId(),
                ArtifactType.DIAGNOSIS,
                "Whitespace mismatch on name".getBytes(StandardCharsets.UTF_8),
                Map.of("task", "run"));
        sessionStore.save(session.toBuilder()
                .currentConfigArtifactId(configArtifact.getArtifactId())
                .title("Compare users")
                .build());
        memoryStore.add(AiMemoryCandidate.builder()
                .type("diagnosis")
                .content("Trim surrounding whitespace before comparing the name field.")
                .source("run:repair-session")
                .build());

        AtomicReference<ConfigGenerationRequest> requestRef = new AtomicReference<>();
        RepairTask task = new RepairTask(new CapturingConfigCapability(requestRef), sessionStore, artifactStore, memoryStore);
        Path output = tempDir.resolve("repair.yaml");

        task.execute(AiTaskContext.builder()
                .session(sessionStore.load("repair-session").orElseThrow())
                .attribute(AiRuntimeContextKeys.OUTPUT_PATH, output.toString())
                .build());

        assertTrue(requestRef.get().getGoal().contains("Relevant runtime memory:"));
        assertTrue(requestRef.get().getGoal().contains("Trim surrounding whitespace"));
        assertTrue(requestRef.get().getGoal().contains("Whitespace mismatch on name"));
        assertTrue(Files.exists(output));
        assertEquals("source:\n  type: mysql\n", Files.readString(output));
        List<AiMemory> repairMemories = memoryStore.list().stream()
                .filter(memory -> "repair".equals(memory.getType()))
                .collect(Collectors.toList());
        assertEquals(1, repairMemories.size());
        assertFalse(repairMemories.get(0).getContent().contains("Relevant runtime memory:"));
        assertTrue(repairMemories.get(0).getContent().contains("Repair based on latest diagnosis:"));
    }

    private String minimalConfig() {
        return "source:\n"
                + "  type: mysql\n"
                + "  name: source-db\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/source\n"
                + "    username: ${env.MYSQL_USER}\n"
                + "    password: ${env.MYSQL_PASSWORD}\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "target:\n"
                + "  type: postgresql\n"
                + "  name: target-db\n"
                + "  connection:\n"
                + "    url: jdbc:postgresql://localhost:5432/target\n"
                + "    username: ${env.PG_USER}\n"
                + "    password: ${env.PG_PASSWORD}\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name, email]\n"
                + "    target: [name, email]\n"
                + "strategy:\n"
                + "  mode: checksum\n"
                + "  algorithm: xor\n"
                + "  batchSize: 1000\n"
                + "  maxDifferences: 100\n";
    }

    private static class CapturingConfigCapability implements ConfigCapability {

        private final AtomicReference<ConfigGenerationRequest> requestRef;

        private CapturingConfigCapability(AtomicReference<ConfigGenerationRequest> requestRef) {
            this.requestRef = requestRef;
        }

        @Override
        public GeneratedConfig generate(ConfigGenerationRequest request) {
            requestRef.set(request);
            return GeneratedConfig.builder()
                    .configRef(ConfigRef.builder()
                            .sessionId(request.getSessionId())
                            .content("source:\n  type: mysql\n")
                            .build())
                    .build();
        }

        @Override
        public ValidationReport validate(ConfigRef configRef) {
            return ValidationReport.builder().passed(true).message("ok").build();
        }

        @Override
        public DryRunReport dryRun(ConfigRef configRef) {
            return DryRunReport.builder().passed(true).message("ok").build();
        }

        @Override
        public ExplainReport explain(ConfigRef configRef) {
            return ExplainReport.builder().markdown("ok").build();
        }
    }
}
