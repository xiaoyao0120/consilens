package com.consilens.cli.ai.runtime;

import com.consilens.ai.session.model.AiSession;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileAiStoresTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistSessionsAndArtifacts() {
        AiRuntimePaths paths = new AiRuntimePaths(tempDir.toString());
        FileAiSessionStore sessionStore = new FileAiSessionStore(paths);
        FileAiArtifactStore artifactStore = new FileAiArtifactStore(paths);

        AiSession session = sessionStore.create("test-session");
        ArtifactRef artifact = artifactStore.write("test-session", ArtifactType.CONFIG,
                "yaml".getBytes(StandardCharsets.UTF_8), Map.of("kind", "test"));

        assertEquals("test-session", sessionStore.load("test-session").orElseThrow().getSessionId());
        assertEquals(artifact.getArtifactId(), artifactStore.latest("test-session", ArtifactType.CONFIG).orElseThrow().getArtifactId());
        assertArrayEquals("yaml".getBytes(StandardCharsets.UTF_8), artifactStore.read(artifact.getArtifactId()).orElseThrow());
        assertTrue(artifactStore.get(artifact.getArtifactId()).isPresent());
    }

    @Test
    void shouldLoadLegacyMemoriesWithoutCreatedAt() throws Exception {
        AiRuntimePaths paths = new AiRuntimePaths(tempDir.toString());
        FileAiMemoryStore memoryStore = new FileAiMemoryStore(paths);
        Files.createDirectories(paths.baseDir());
        Files.writeString(paths.memoriesFile(),
                "[{\"memoryId\":\"m1\",\"type\":\"goal\",\"content\":\"Compare users\",\"source\":\"plan:test-session\"}]");

        List<com.consilens.ai.session.model.AiMemory> memories = memoryStore.list();

        assertEquals(1, memories.size());
        assertEquals(Instant.EPOCH, memories.get(0).getCreatedAt());
    }
}
