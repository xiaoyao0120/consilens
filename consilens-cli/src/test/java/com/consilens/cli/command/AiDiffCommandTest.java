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

class AiDiffCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateConfigWithoutExecutingDiff() throws Exception {
        Path output = tempDir.resolve("ai-diff.yaml");
        AtomicReference<String> session = new AtomicReference<>();
        AtomicReference<AiTaskContext> context = new AtomicReference<>();

        int exitCode = new CommandLine(new AiDiffCommand(() -> new AiConversationRuntime() {
            @Override
            public AiTurnResult handleUserInput(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiTurnResult executeCommand(String sessionId, AiTaskContext taskContext) {
                session.set(sessionId);
                context.set(taskContext);
                try {
                    Files.writeString(Path.of(taskContext.attribute("outputPath", String.class)), "source:\n  type: mysql\n");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                return AiTurnResult.builder()
                        .status(AiTurnResult.Status.COMPLETED)
                        .message("planned")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        })).execute(
                "--no-llm",
                "--source-type", "mysql",
                "--source-url", "jdbc:mysql://localhost:3306/source",
                "--source-table", "orders",
                "--source-user-env", "MYSQL_USER",
                "--source-password-env", "MYSQL_PASSWORD",
                "--target-type", "postgresql",
                "--target-url", "jdbc:postgresql://localhost:5432/target",
                "--target-table", "orders",
                "--target-user-env", "PG_USER",
                "--target-password-env", "PG_PASSWORD",
                "--keys", "id",
                "--output", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        assertEquals("plan", context.get().getCommand().getName());
        assertTrue(context.get().attribute("configRequest").toString().contains("orders"));
        assertTrue(session.get().startsWith("ai-"));
    }

    @Test
    void shouldDispatchExecuteFlagToRuntime() {
        AtomicReference<String> session = new AtomicReference<>();
        AtomicReference<AiTaskContext> context = new AtomicReference<>();

        AiConversationRuntime runtime = new AiConversationRuntime() {
            @Override
            public AiTurnResult handleUserInput(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiTurnResult executeCommand(String sessionId, AiTaskContext taskContext) {
                session.set(sessionId);
                context.set(taskContext);
                return AiTurnResult.builder()
                        .status(AiTurnResult.Status.REQUIRES_APPROVAL)
                        .message("approval required")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };

        int exitCode = new CommandLine(new AiDiffCommand(() -> runtime)).execute(
                "--session", "diff-run-1",
                "--execute",
                "--no-llm",
                "--source-type", "mysql",
                "--source-url", "jdbc:mysql://localhost:3306/source",
                "--source-table", "orders",
                "--target-type", "postgresql",
                "--target-url", "jdbc:postgresql://localhost:5432/target",
                "--target-table", "orders",
                "--keys", "id");

        assertEquals(2, exitCode);
        assertEquals("diff-run-1", session.get());
        assertEquals("run", context.get().getCommand().getName());
    }
}
