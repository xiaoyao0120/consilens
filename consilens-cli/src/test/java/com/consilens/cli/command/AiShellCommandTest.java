package com.consilens.cli.command;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.orchestrator.AiConversationRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiShellCommandTest {

    @Test
    void shouldForwardExplainPathFromShellCommand() throws Exception {
        AiTaskContext context = invoke("shell-1", "/explain /tmp/config.yaml");

        assertEquals("explain", context.getCommand().getName());
        assertEquals("/tmp/config.yaml", context.attribute("configPath", String.class));
    }

    @Test
    void shouldForwardRunApprovalAndGoalFromShellCommand() throws Exception {
        AiTaskContext context = invoke("shell-2", "/run --approve-execute compare orders");

        assertEquals("run", context.getCommand().getName());
        assertTrue(context.attribute("approveExecute", Boolean.class));
        assertEquals(ApprovalMode.EXPLICIT_FLAG, context.attribute("approvalMode", ApprovalMode.class));
        ConfigGenerationRequest request = context.attribute("configRequest", ConfigGenerationRequest.class);
        assertEquals("shell-2", request.getSessionId());
        assertEquals("compare orders", request.getGoal());
    }

    private AiTaskContext invoke(String sessionId, String line) throws Exception {
        AiShellCommand command = new AiShellCommand(() -> new AiConversationRuntime() {
            @Override
            public com.consilens.ai.runtime.model.AiTurnResult handleUserInput(String sessionId, String input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.consilens.ai.runtime.model.AiTurnResult executeCommand(String sessionId, AiTaskContext taskContext) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.consilens.ai.runtime.model.AiSessionSnapshot snapshot(String sessionId) {
                throw new UnsupportedOperationException();
            }
        });
        Method method = AiShellCommand.class.getDeclaredMethod("commandContext", String.class, String.class);
        method.setAccessible(true);
        return (AiTaskContext) method.invoke(command, sessionId, line);
    }
}
