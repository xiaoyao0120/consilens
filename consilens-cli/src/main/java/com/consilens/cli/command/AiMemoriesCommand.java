package com.consilens.cli.command;

import com.consilens.ai.session.model.AiMemory;
import com.consilens.cli.ai.runtime.AiRuntimePaths;
import com.consilens.cli.ai.runtime.FileAiMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Lists persisted AI runtime memories.
 */
@Command(
    name = "memories",
    description = "List persisted AI runtime memories",
    mixinStandardHelpOptions = true
)
public class AiMemoriesCommand implements Callable<Integer> {

    private final FileAiMemoryStore memoryStore;
    private final ObjectMapper jsonMapper;

    @Option(names = "--session", description = "Filter memories whose source contains the session ID")
    private String sessionId;

    @Option(names = "--type", description = "Filter by memory type")
    private String type;

    @Option(names = "--limit", defaultValue = "20", description = "Maximum number of memories to show")
    private int limit;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Spec
    private CommandSpec spec;

    public AiMemoriesCommand() {
        this(new FileAiMemoryStore(new AiRuntimePaths()), defaultObjectMapper());
    }

    AiMemoriesCommand(FileAiMemoryStore memoryStore, ObjectMapper jsonMapper) {
        this.memoryStore = memoryStore;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Integer call() throws Exception {
        List<AiMemory> memories = filtered();
        if ("json".equalsIgnoreCase(format)) {
            PrintWriter out = spec.commandLine().getOut();
            out.println(jsonMapper.writeValueAsString(memories));
            out.flush();
            return 0;
        }
        if (!"text".equalsIgnoreCase(format)) {
            spec.commandLine().getErr().println("Unsupported format: " + format + ". Use text or json.");
            spec.commandLine().getErr().flush();
            return 2;
        }
        PrintWriter out = spec.commandLine().getOut();
        out.println("# AI Memories");
        out.println();
        if (memories.isEmpty()) {
            out.println("No persisted memories found.");
            out.flush();
            return 0;
        }
        for (AiMemory memory : memories) {
            out.println("- [" + memory.getType() + "] " + memory.getContent());
            out.println("  id=" + memory.getMemoryId());
            if (memory.getSource() != null && !memory.getSource().isBlank()) {
                out.println("  source=" + memory.getSource());
            }
            if (memory.getCreatedAt() != null) {
                out.println("  createdAt=" + memory.getCreatedAt());
            }
        }
        out.flush();
        return 0;
    }

    private List<AiMemory> filtered() {
        return memoryStore.list().stream()
                .filter(memory -> sessionId == null || sessionId.isBlank()
                        || (memory.getSource() != null && memory.getSource().contains(sessionId.trim())))
                .filter(memory -> type == null || type.isBlank()
                        || memory.getType() != null && memory.getType().toLowerCase(Locale.ROOT).equals(type.trim().toLowerCase(Locale.ROOT)))
                .sorted((left, right) -> createdAt(right).compareTo(createdAt(left)))
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    private Instant createdAt(AiMemory memory) {
        return memory == null || memory.getCreatedAt() == null ? Instant.EPOCH : memory.getCreatedAt();
    }

    private static ObjectMapper defaultObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
