package com.consilens.cli.command;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.model.AiSessionSnapshot;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiPlanCommandTest {

    @Test
    void shouldPassConfigGenerationRequestIntoRuntime() {
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
                        .message("planned")
                        .build();
            }

            @Override
            public AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };

        int exitCode = new CommandLine(new AiPlanCommand(() -> runtime)).execute(
                "--session", "s-1",
                "--no-llm",
                "--source-type", "mysql",
                "--source-url", "jdbc:mysql://localhost:3306/source",
                "--source-table", "users",
                "--target-type", "postgresql",
                "--target-url", "jdbc:postgresql://localhost:5432/target",
                "--target-table", "users",
                "--keys", "id");

        assertEquals(0, exitCode);
        assertEquals("s-1", session.get());
        ConfigGenerationRequest request = context.get().attribute("configRequest", ConfigGenerationRequest.class);
        assertNotNull(request);
        assertEquals("s-1", request.getSessionId());
    }
}
