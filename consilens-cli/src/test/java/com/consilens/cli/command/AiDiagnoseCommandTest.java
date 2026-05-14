package com.consilens.cli.command;

import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDiagnoseCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDiagnoseDiffRecordArray() throws Exception {
        Path result = tempDir.resolve("diff-records.json");
        Files.writeString(result,
                "[{"
                        + "\"operation\":\"MISMATCH\","
                        + "\"primaryKey\":[1],"
                        + "\"sourceValues\":[\"Alice\"],"
                        + "\"targetValues\":[\"\"],"
                        + "\"columnNames1\":[\"name\"],"
                        + "\"columnNames2\":[\"name\"]"
                        + "}]");

        int exitCode = new CommandLine(new AiDiagnoseCommand()).execute("--result", result.toString());

        assertEquals(0, exitCode);
    }

    @Test
    void shouldFailWhenResultDoesNotContainDiffEvidence() throws Exception {
        Path result = tempDir.resolve("stats-only.json");
        Files.writeString(result, "{\"differenceCount\":1}");

        int exitCode = new CommandLine(new AiDiagnoseCommand()).execute("--result", result.toString());

        assertEquals(1, exitCode);
    }

    @Test
    void shouldUseInjectedDiagnoseService() {
        AtomicReference<String> analyzer = new AtomicReference<>();
        AtomicReference<String> resultPath = new AtomicReference<>();

        int exitCode = new CommandLine(new AiDiagnoseCommand(() -> new AiConversationRuntime() {
            @Override
            public AiTurnResult handleUserInput(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiTurnResult executeCommand(String sessionId, AiTaskContext taskContext) {
                analyzer.set(taskContext.attribute("analyzer", String.class));
                resultPath.set(taskContext.attribute("evidencePath", String.class));
                return AiTurnResult.builder()
                        .status(AiTurnResult.Status.COMPLETED)
                        .message("diagnosed")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        })).execute("--result", "diff.json", "--analyzer", "custom");

        assertEquals(0, exitCode);
        assertEquals("custom", analyzer.get());
        assertEquals("diff.json", resultPath.get());
    }

    @Test
    void shouldUseRuleBasedAnalyzerByDefault() {
        AtomicReference<String> analyzer = new AtomicReference<>();

        int exitCode = new CommandLine(new AiDiagnoseCommand(() -> new AiConversationRuntime() {
            @Override
            public AiTurnResult handleUserInput(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiTurnResult executeCommand(String sessionId, AiTaskContext taskContext) {
                analyzer.set(taskContext.attribute("analyzer", String.class));
                return AiTurnResult.builder()
                        .status(AiTurnResult.Status.COMPLETED)
                        .message("diagnosed")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        })).execute("--result", "diff.json");

        assertEquals(0, exitCode);
        assertEquals("rulebased", analyzer.get());
    }

    @Test
    void shouldWriteDiagnosisReportToOutputFile() throws Exception {
        Path output = tempDir.resolve("reports/diagnose.md");

        int exitCode = new CommandLine(new AiDiagnoseCommand(() -> new AiConversationRuntime() {
            @Override
            public AiTurnResult handleUserInput(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiTurnResult executeCommand(String sessionId, AiTaskContext taskContext) {
                try {
                    Path requestedOutput = Path.of(taskContext.attribute("outputPath", String.class));
                    Files.createDirectories(requestedOutput.getParent());
                    Files.writeString(requestedOutput, "# report");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return AiTurnResult.builder()
                        .status(AiTurnResult.Status.COMPLETED)
                        .message("diagnosed")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        })).execute("--result", "diff.json", "--output", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        assertEquals("# report", Files.readString(output));
    }
}
