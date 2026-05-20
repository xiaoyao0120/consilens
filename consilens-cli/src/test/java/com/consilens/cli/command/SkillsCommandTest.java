package com.consilens.cli.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillsCommandTest {

    @Test
    void shouldListSkillsAsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new SkillsCommand());
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute("--format", "json");

        assertEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("compare-config-build"));
        assertTrue(output.contains("consilens.validate.config"));
    }
}
