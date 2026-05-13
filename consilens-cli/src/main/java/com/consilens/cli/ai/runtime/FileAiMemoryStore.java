package com.consilens.cli.ai.runtime;

import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.model.AiMemory;
import com.consilens.ai.session.model.AiMemoryCandidate;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * File-backed memory store for non-sensitive AI memories.
 */
public class FileAiMemoryStore implements AiMemoryStore {

    private final AiRuntimePaths paths;
    private final ObjectMapper mapper;

    public FileAiMemoryStore(AiRuntimePaths paths) {
        this.paths = paths;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public synchronized List<AiMemory> list() {
        if (!Files.exists(paths.memoriesFile())) {
            return List.of();
        }
        try {
            return mapper.readValue(paths.memoriesFile().toFile(), new TypeReference<List<AiMemory>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read AI memories", e);
        }
    }

    @Override
    public synchronized Optional<AiMemory> add(AiMemoryCandidate candidate) {
        if (candidate == null || candidate.getContent() == null || candidate.getContent().trim().isEmpty()) {
            return Optional.empty();
        }
        AiMemory memory = AiMemory.builder()
                .memoryId("memory-" + UUID.randomUUID())
                .type(candidate.getType())
                .content(candidate.getContent().trim())
                .createdAt(Instant.now())
                .build();
        List<AiMemory> memories = new ArrayList<>(list());
        memories.add(memory);
        write(memories);
        return Optional.of(memory);
    }

    @Override
    public synchronized void remove(String memoryId) {
        write(list().stream()
                .filter(memory -> !memory.getMemoryId().equals(memoryId))
                .collect(Collectors.toList()));
    }

    private void write(List<AiMemory> memories) {
        try {
            Files.createDirectories(paths.baseDir());
            mapper.writeValue(paths.memoriesFile().toFile(), memories);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write AI memories", e);
        }
    }
}
