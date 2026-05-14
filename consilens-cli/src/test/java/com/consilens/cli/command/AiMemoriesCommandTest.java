package com.consilens.cli.command;

import com.consilens.ai.session.model.AiMemory;
import com.consilens.cli.ai.runtime.AiRuntimePaths;
import com.consilens.cli.ai.runtime.FileAiMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiMemoriesCommandTest {

    @Test
    void shouldListSessionMemoriesAsText() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new AiMemoriesCommand(new StubMemoryStore(), jsonMapper()));
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute("--session", "s-1");

        assertEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("# AI Memories"));
        assertTrue(output.contains("[goal] Compare orders"));
        assertTrue(output.contains("source=run:s-1"));
    }

    @Test
    void shouldListMemoriesAsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new AiMemoriesCommand(new StubMemoryStore(), jsonMapper()));
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute("--format", "json", "--type", "diagnosis");

        assertEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"type\":\"diagnosis\""));
    }

    @Test
    void shouldListLegacyMemoriesWithoutTimestamp() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new AiMemoriesCommand(new LegacyStubMemoryStore(), jsonMapper()));
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("[goal] Legacy memory"));
    }

    private static class StubMemoryStore extends FileAiMemoryStore {

        private StubMemoryStore() {
            super(new AiRuntimePaths());
        }

        @Override
        public synchronized List<AiMemory> list() {
            return List.of(
                    AiMemory.builder()
                            .memoryId("m1")
                            .type("goal")
                            .content("Compare orders")
                            .source("run:s-1")
                            .createdAt(Instant.parse("2026-05-13T09:00:00Z"))
                            .build(),
                    AiMemory.builder()
                            .memoryId("m2")
                            .type("diagnosis")
                            .content("Whitespace mismatch on name")
                            .source("diagnose:s-2")
                            .createdAt(Instant.parse("2026-05-13T10:00:00Z"))
                            .build()
            );
        }
    }

    private static class LegacyStubMemoryStore extends FileAiMemoryStore {

        private LegacyStubMemoryStore() {
            super(new AiRuntimePaths());
        }

        @Override
        public synchronized List<AiMemory> list() {
            return List.of(AiMemory.builder()
                    .memoryId("legacy")
                    .type("goal")
                    .content("Legacy memory")
                    .source("plan:s-1")
                    .createdAt(null)
                    .build());
        }
    }

    private static ObjectMapper jsonMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
