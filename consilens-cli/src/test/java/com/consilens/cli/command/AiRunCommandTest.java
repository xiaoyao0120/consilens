package com.consilens.cli.command;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRunCommandTest {

    @Test
    void shouldPassApprovalAndOptionalConfigRequestIntoRuntime() {
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
                        .message("ran")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };

        int exitCode = new CommandLine(new AiRunCommand(() -> runtime)).execute(
                "--session", "s-2",
                "--approve-execute",
                "--no-llm",
                "--source-type", "mysql",
                "--source-url", "jdbc:mysql://localhost:3306/source",
                "--source-table", "orders",
                "--target-type", "postgresql",
                "--target-url", "jdbc:postgresql://localhost:5432/target",
                "--target-table", "orders",
                "--keys", "id");

        assertEquals(0, exitCode);
        assertTrue(context.get().attribute("approveExecute", Boolean.class));
        assertEquals(ApprovalMode.EXPLICIT_FLAG, context.get().attribute("approvalMode", ApprovalMode.class));
        ConfigGenerationRequest request = context.get().attribute("configRequest", ConfigGenerationRequest.class);
        assertEquals("s-2", request.getSessionId());
    }

    @Test
    void shouldReuseSessionConfigWhenNoGenerationInputIsProvided() {
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
                        .status(AiTurnResult.Status.REQUIRES_APPROVAL)
                        .message("approval required")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };

        int exitCode = new CommandLine(new AiRunCommand(() -> runtime)).execute("--session", "s-3");

        assertEquals(2, exitCode);
        assertNull(context.get().attribute("configRequest"));
    }
}
