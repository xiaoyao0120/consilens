package com.consilens.cli.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCommandTest {

    @Test
    void shouldListMcpToolsAsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new McpCommand());
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute("tools", "--format", "json");

        assertEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("consilens.plan.config"));
        assertTrue(output.contains("/v1/plan"));
    }
}
