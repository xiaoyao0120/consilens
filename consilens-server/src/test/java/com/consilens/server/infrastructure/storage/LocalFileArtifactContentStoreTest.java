package com.consilens.server.infrastructure.storage;

import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enumtype.ArtifactKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileArtifactContentStoreTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldSanitizeUnsafeArtifactFormatExtension() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getArtifact().setLocalBaseDir(tempDir.toString());
        LocalFileArtifactContentStore store = new LocalFileArtifactContentStore(properties);

        StoredArtifactContent content = store.write("artifact-test",
                ArtifactKind.RUN_RESULT,
                "../json",
                "{}".getBytes());

        Path storagePath = Path.of(content.getStorageUri());
        assertThat(storagePath.getParent()).isEqualTo(tempDir.toAbsolutePath());
        assertThat(storagePath.getFileName().toString()).isEqualTo("artifact-test.bin");
    }
}
