package com.consilens.cli.ai;

import com.consilens.cli.ai.runtime.AiRuntimePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads persistent LLM backend defaults from the local runtime home.
 */
public class AiBackendDefaultsStore {

    private final AiRuntimePaths paths;
    private final ObjectMapper mapper;

    public AiBackendDefaultsStore(AiRuntimePaths paths) {
        this.paths = paths;
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public Optional<AiBackendDefaults> load() {
        Path path = location();
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(path.toFile(), AiBackendDefaults.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read AI backend defaults from " + path, e);
        }
    }

    public Path location() {
        return paths.backendDefaultsFile();
    }
}
