package com.consilens.cli.command;

import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiRepairCommandTest {

    @Test
    void shouldDispatchRepairCommandToRuntime() {
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
                        .status(AiTurnResult.Status.COMPLETED)
                        .message("repaired")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };

        int exitCode = new CommandLine(new AiRepairCommand(() -> runtime)).execute("--session", "repair-1");

        assertEquals(0, exitCode);
        assertEquals("repair-1", session.get());
        assertEquals("repair", context.get().getCommand().getName());
    }

    @Test
    void shouldForwardRepairOutputPathToRuntime() {
        AtomicReference<AiTaskContext> context = new AtomicReference<>();

        AiConversationRuntime runtime = new AiConversationRuntime() {
            @Override
            public AiTurnResult handleUserInput(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AiTurnResult executeCommand(String sessionId, AiTaskContext taskContext) {
                context.set(taskContext);
                return AiTurnResult.builder()
                        .status(AiTurnResult.Status.COMPLETED)
                        .message("repaired")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };

        int exitCode = new CommandLine(new AiRepairCommand(() -> runtime)).execute(
                "--session", "repair-2",
                "--output", "repairs/fixed.yaml");

        assertEquals(0, exitCode);
        assertEquals("repairs/fixed.yaml", context.get().attribute("outputPath", String.class));
    }
}
