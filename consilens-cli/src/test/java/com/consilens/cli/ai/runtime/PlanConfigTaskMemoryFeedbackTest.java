package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.ExplainReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.session.model.AiMemoryCandidate;
import com.consilens.ai.session.model.AiSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanConfigTaskMemoryFeedbackTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldInjectRelevantMemoryIntoGenerationGoal() {
        AiRuntimePaths paths = new AiRuntimePaths(tempDir.toString());
        FileAiSessionStore sessionStore = new FileAiSessionStore(paths);
        FileAiArtifactStore artifactStore = new FileAiArtifactStore(paths);
        FileAiMemoryStore memoryStore = new FileAiMemoryStore(paths);
        AiSession session = sessionStore.create("memory-session");
        memoryStore.add(AiMemoryCandidate.builder()
                .type("diagnosis")
                .content("Name column often mismatches because source trims whitespace differently.")
                .source("run:memory-session")
                .build());

        AtomicReference<ConfigGenerationRequest> requestRef = new AtomicReference<>();
        PlanConfigTask task = new PlanConfigTask(new CapturingConfigCapability(requestRef), sessionStore, artifactStore, memoryStore);

        task.execute(AiTaskContext.builder()
                .session(session)
                .attribute(AiRuntimeContextKeys.CONFIG_REQUEST, ConfigGenerationRequest.builder()
                        .sessionId("memory-session")
                        .goal("Generate users diff config for name/email reconciliation")
                        .hint("sourceType=mysql")
                        .hint("sourceUrl=jdbc:mysql://localhost:3306/source")
                        .hint("sourceTable=users")
                        .hint("targetType=postgresql")
                        .hint("targetUrl=jdbc:postgresql://localhost:5432/target")
                        .hint("targetTable=users")
                        .hint("keys=id")
                        .build())
                .build());

        assertTrue(requestRef.get().getGoal().contains("Relevant runtime memory:"));
        assertTrue(requestRef.get().getGoal().contains("trims whitespace"));
        assertEquals("Generate users diff config for name/email reconciliation",
                sessionStore.load("memory-session").orElseThrow().getTitle());
        List<com.consilens.ai.session.model.AiMemory> goalMemories = memoryStore.list().stream()
                .filter(memory -> "goal".equals(memory.getType()))
                .collect(Collectors.toList());
        assertEquals(1, goalMemories.size());
        assertEquals("Generate users diff config for name/email reconciliation", goalMemories.get(0).getContent());
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
