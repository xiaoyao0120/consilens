package com.consilens.cli.ai.runtime;

import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * File-backed artifact store with a global JSON index.
 */
public class FileAiArtifactStore implements AiArtifactStore {

    private final AiRuntimePaths paths;
    private final ObjectMapper mapper;

    public FileAiArtifactStore(AiRuntimePaths paths) {
        this.paths = paths;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public synchronized ArtifactRef write(String sessionId, ArtifactType type, byte[] content, Map<String, String> metadata) {
        try {
            String artifactId = type.name().toLowerCase() + "-" + UUID.randomUUID();
            Path sessionDir = paths.sessionArtifactDir(sessionId);
            Files.createDirectories(sessionDir);
            Path file = sessionDir.resolve(artifactId + extension(type));
            Files.write(file, content);

            ArtifactRef ref = ArtifactRef.builder()
                    .artifactId(artifactId)
                    .sessionId(sessionId)
                    .type(type)
                    .path(file.toString())
                    .sha256(sha256(content))
                    .metadata(metadata == null ? Map.of() : metadata)
                    .createdAt(Instant.now())
                    .build();

            List<ArtifactRef> refs = readIndex();
            refs.add(ref);
            writeIndex(refs);
            return ref;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist AI artifact for session " + sessionId, e);
        }
    }

    @Override
    public synchronized Optional<ArtifactRef> get(String artifactId) {
        return readIndex().stream()
                .filter(ref -> ref.getArtifactId().equals(artifactId))
                .findFirst();
    }

    @Override
    public synchronized Optional<byte[]> read(String artifactId) {
        return get(artifactId).map(ref -> {
            try {
                return Files.readAllBytes(Path.of(ref.getPath()));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read AI artifact " + artifactId, e);
            }
        });
    }

    @Override
    public synchronized Optional<ArtifactRef> latest(String sessionId, ArtifactType type) {
        return readIndex().stream()
                .filter(ref -> ref.getSessionId().equals(sessionId) && ref.getType() == type)
                .max(Comparator.comparing(ArtifactRef::getCreatedAt));
    }

    @Override
    public synchronized List<ArtifactRef> list(String sessionId) {
        return readIndex().stream()
                .filter(ref -> ref.getSessionId().equals(sessionId))
                .sorted(Comparator.comparing(ArtifactRef::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    private List<ArtifactRef> readIndex() {
        Path indexPath = paths.artifactIndexFile();
        if (!Files.exists(indexPath)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(indexPath.toFile(), new TypeReference<List<ArtifactRef>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read AI artifact index", e);
        }
    }

    private void writeIndex(List<ArtifactRef> refs) {
        try {
            Files.createDirectories(paths.artifactsDir());
            mapper.writeValue(paths.artifactIndexFile().toFile(), refs);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write AI artifact index", e);
        }
    }

    private String extension(ArtifactType type) {
        switch (type) {
            case CONFIG:
                return ".yaml";
            case DIFF_RESULT:
            case DIFF_EVIDENCE:
                return ".json";
            default:
                return ".txt";
        }
    }

    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash AI artifact", e);
        }
    }
}
