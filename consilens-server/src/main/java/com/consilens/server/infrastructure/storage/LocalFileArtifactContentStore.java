package com.consilens.server.infrastructure.storage;

import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enumtype.ArtifactKind;
import com.consilens.server.support.hash.Sha256Support;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LocalFileArtifactContentStore implements ArtifactContentStore {

    private final ConsilensServerProperties properties;

    public LocalFileArtifactContentStore(ConsilensServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredArtifactContent write(String artifactId, ArtifactKind artifactKind, String format, byte[] content) {
        try {
            Path baseDir = Path.of(properties.getArtifact().getLocalBaseDir());
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(artifactId + extension(format));
            Files.write(file, content);
            return StoredArtifactContent.builder()
                    .storageType("LOCAL_FILE")
                    .storageUri(file.toAbsolutePath().toString())
                    .sha256(Sha256Support.hex(content))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist artifact content for " + artifactId, exception);
        }
    }

    @Override
    public byte[] read(String storageUri) {
        try {
            return Files.readAllBytes(Path.of(storageUri));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read artifact content from " + storageUri, exception);
        }
    }

    private String extension(String format) {
        if (format == null || format.isBlank()) {
            return ".txt";
        }
        String normalized = format.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9_-]{1,16}")) {
            return ".bin";
        }
        return "." + normalized;
    }
}
