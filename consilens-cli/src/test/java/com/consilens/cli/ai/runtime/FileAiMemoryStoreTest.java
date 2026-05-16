package com.consilens.cli.ai.runtime;

import com.consilens.ai.session.model.AiMemoryCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAiMemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRejectSensitiveMemoryContent() {
        FileAiMemoryStore store = new FileAiMemoryStore(new AiRuntimePaths(tempDir.toString()));

        assertTrue(store.add(AiMemoryCandidate.builder()
                .type("connection")
                .content("password=secret123")
                .source("manual:test")
                .build()).isEmpty());
        assertEquals(0, store.list().size());
    }

    @Test
    void shouldPersistSafeMemoryContent() {
        FileAiMemoryStore store = new FileAiMemoryStore(new AiRuntimePaths(tempDir.toString()));

        assertTrue(store.add(AiMemoryCandidate.builder()
                .type("project")
                .content("repo uses diff-record json for diagnosis")
                .source("manual:test")
                .build()).isPresent());
        assertEquals(1, store.list().size());
    }
}
