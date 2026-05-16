package com.consilens.cli.command;

import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.model.AiTaskContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConsoleContextParserTest {

    private final AiConsoleContextParser parser = new AiConsoleContextParser();

    @Test
    void shouldForwardExplainPathFromShellCommand() {
        AiTaskContext context = parser.parse("shell-1", "/explain /tmp/config.yaml");

        assertEquals("explain", context.getCommand().getName());
        assertEquals("/tmp/config.yaml", context.attribute("configPath", String.class));
    }

    @Test
    void shouldForwardRunApprovalAndGoalFromShellCommand() {
        AiTaskContext context = parser.parse("shell-2", "/run --approve-execute compare orders");

        assertEquals("run", context.getCommand().getName());
        assertTrue(context.attribute("approveExecute", Boolean.class));
        assertEquals(ApprovalMode.EXPLICIT_FLAG, context.attribute("approvalMode", ApprovalMode.class));
        ConfigGenerationRequest request = context.attribute("configRequest", ConfigGenerationRequest.class);
        assertEquals("shell-2", request.getSessionId());
        assertEquals("compare orders", request.getGoal());
    }

    @Test
    void shouldParsePlanWithoutGoal() {
        AiTaskContext context = parser.parse("shell-2b", "/plan");

        assertEquals("plan", context.getCommand().getName());
        assertEquals("", context.getCommand().getArgument());
    }

    @Test
    void shouldMapCheckToExplainWithDryRun() {
        AiTaskContext context = parser.parse("shell-3", "/check");

        assertEquals("check", context.getCommand().getName());
        assertTrue(context.attribute("performDryRun", Boolean.class));
        assertTrue(context.attribute("inlineOutput", Boolean.class));
    }

    @Test
    void shouldParseValidateAndDryRunCommands() {
        AiTaskContext validate = parser.parse("shell-4", "/validate /tmp/current.yaml");
        AiTaskContext dryRun = parser.parse("shell-4", "/dry-run /tmp/current.yaml");

        assertEquals("validate", validate.getCommand().getName());
        assertEquals("/tmp/current.yaml", validate.attribute("configPath", String.class));
        assertTrue(validate.attribute("inlineOutput", Boolean.class));

        assertEquals("dry-run", dryRun.getCommand().getName());
        assertEquals("/tmp/current.yaml", dryRun.attribute("configPath", String.class));
        assertTrue(dryRun.attribute("inlineOutput", Boolean.class));
    }

    @Test
    void shouldParseDiffAndAnalyzeLastCommands() {
        AiTaskContext diff = parser.parse("shell-5", "/diff --approve-execute");
        AiTaskContext analyzeLast = parser.parse("shell-5", "/analyze-last");

        assertEquals("diff", diff.getCommand().getName());
        assertTrue(diff.attribute("approveExecute", Boolean.class));
        assertEquals(ApprovalMode.EXPLICIT_FLAG, diff.attribute("approvalMode", ApprovalMode.class));

        assertEquals("analyze-last", analyzeLast.getCommand().getName());
        assertTrue(analyzeLast.attribute("inlineOutput", Boolean.class));
    }

    @Test
    void shouldParseRememberAndForgetCommands() {
        AiTaskContext remember = parser.parse("shell-6", "/remember project repo uses diff-record json");
        AiTaskContext forget = parser.parse("shell-6", "/forget memory-1");

        assertEquals("remember", remember.getCommand().getName());
        assertEquals("project", remember.attribute("memoryType", String.class));
        assertEquals("repo uses diff-record json", remember.attribute("memoryContent", String.class));

        assertEquals("forget", forget.getCommand().getName());
        assertEquals("memory-1", forget.attribute("memoryId", String.class));
    }

    @Test
    void shouldParseUseConfigCommand() {
        AiTaskContext context = parser.parse("shell-7", "/use-config /tmp/current.yaml");

        assertEquals("use-config", context.getCommand().getName());
        assertEquals("/tmp/current.yaml", context.attribute("configPath", String.class));
    }
}
