package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.ai.conversation.api.model.ConversationCommandRequest;
import com.consilens.ai.conversation.api.model.ConversationEventDto;
import com.consilens.ai.conversation.api.model.ConversationResponse;
import com.consilens.ai.conversation.api.model.ArtifactContentResponse;
import com.consilens.ai.conversation.api.model.ArtifactEntryDto;
import com.consilens.ai.conversation.api.model.CurrentConfigResponse;
import com.consilens.ai.conversation.api.model.MemoryEntryDto;
import com.consilens.ai.conversation.api.model.SaveConfigResponse;
import com.consilens.ai.conversation.api.model.SessionRecoveryResponse;
import com.consilens.ai.conversation.api.model.SessionSnapshot;
import com.consilens.ai.conversation.api.model.SessionSummaryDto;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.runtime.intent.CompareIntentHintExtractor;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.cli.ai.runtime.AiRuntimeContextKeys;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Supplier;

final class AiConsoleLoop {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_DIM = "\u001B[2m";

    private final Supplier<ConversationService> conversationServiceSupplier;
    private final BufferedReader reader;
    private final PrintStream out;
    private final AiConsoleContextParser contextParser;
    private final AiConsoleStartupOptions startupOptions;
    private final boolean interactiveInput;

    AiConsoleLoop(Supplier<ConversationService> conversationServiceSupplier) {
        this(conversationServiceSupplier, System.in, System.out, AiConsoleStartupOptions.builder().build());
    }

    AiConsoleLoop(Supplier<ConversationService> conversationServiceSupplier,
                  InputStream inputStream,
                  PrintStream out) {
        this(conversationServiceSupplier, inputStream, out, AiConsoleStartupOptions.builder().build());
    }

    AiConsoleLoop(Supplier<ConversationService> conversationServiceSupplier,
                  InputStream inputStream,
                  PrintStream out,
                  AiConsoleStartupOptions startupOptions) {
        this.conversationServiceSupplier = conversationServiceSupplier;
        this.reader = new BufferedReader(new InputStreamReader(inputStream));
        this.out = out;
        this.contextParser = new AiConsoleContextParser();
        this.startupOptions = startupOptions == null ? AiConsoleStartupOptions.builder().build() : startupOptions;
        this.interactiveInput = inputStream == System.in;
    }

    int start(String preferredSessionId, boolean forceNewSession, String initialRequest) throws IOException {
        return start(preferredSessionId, forceNewSession, initialRequest, startupOptions);
    }

    int start(String preferredSessionId, boolean forceNewSession, String initialRequest, AiConsoleStartupOptions startupOptions) throws IOException {
        ConversationService conversationService = conversationServiceSupplier.get();
        String currentSessionId = conversationService.startSession(preferredSessionId, forceNewSession).getSessionId();
        if (startupOptions != null && startupOptions.hasConfigPath()) {
            printResponse(currentSessionId, conversationService.executeCommand(useConfigRequest(currentSessionId, startupOptions.getConfigPath())));
        }

        if (initialRequest != null && !initialRequest.isBlank()) {
            ConversationResponse response = submitNaturalInput(conversationService, currentSessionId, initialRequest.trim(), startupOptions);
            printResponse(currentSessionId, response);
            if (!isInteractiveMode()) {
                return exitCode(response);
            }
        }

        printWelcome(currentSessionId, conversationService);
        while (true) {
            out.print("consilens ai> ");
            String line = reader.readLine();
            if (line == null) {
                return 0;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("/quit".equalsIgnoreCase(trimmed) || "/exit".equalsIgnoreCase(trimmed) || "exit".equalsIgnoreCase(trimmed)) {
                return 0;
            }
            if ("/help".equalsIgnoreCase(trimmed)) {
                printHelp();
                continue;
            }
            if ("/sessions".equalsIgnoreCase(trimmed)) {
                printSessions(conversationService.listSessions(20), currentSessionId);
                continue;
            }
            if (trimmed.startsWith("/resume")) {
                String target = trimmed.substring("/resume".length()).trim();
                if (target.isBlank()) {
                    out.println("Session ID is required for /resume.");
                    continue;
                }
                currentSessionId = conversationService.resumeSession(target).getSessionId();
                out.println("Resumed session " + currentSessionId + ".");
                continue;
            }
            if ("/new".equalsIgnoreCase(trimmed)) {
                currentSessionId = conversationService.startSession(null, true).getSessionId();
                out.println("Started new session " + currentSessionId + ".");
                continue;
            }
            if ("/approve execute".equalsIgnoreCase(trimmed)) {
                printResponse(currentSessionId, conversationService.approve(currentSessionId));
                continue;
            }
            if ("/deny".equalsIgnoreCase(trimmed)) {
                printResponse(currentSessionId, conversationService.deny(currentSessionId));
                continue;
            }
            if ("/snapshot".equalsIgnoreCase(trimmed)) {
                printSnapshot(conversationService.getSessionSnapshot(currentSessionId));
                continue;
            }
            if ("/recover".equalsIgnoreCase(trimmed)) {
                printRecovery(conversationService.recoverSession(currentSessionId));
                continue;
            }
            if ("/config".equalsIgnoreCase(trimmed)) {
                printCurrentConfig(conversationService.getCurrentConfig(currentSessionId), currentSessionId);
                continue;
            }
            if (trimmed.startsWith("/save")) {
                String targetPath = trimmed.substring("/save".length()).trim();
                if (targetPath.isBlank()) {
                    out.println("Usage: /save <path>");
                    continue;
                }
                SaveConfigResponse saved = conversationService.saveCurrentConfig(currentSessionId, targetPath);
                out.println(saved.getMessage());
                continue;
            }
            if ("/memories".equalsIgnoreCase(trimmed)) {
                printMemories(conversationService.listMemory(currentSessionId, 10), currentSessionId);
                continue;
            }
            if ("/artifacts".equalsIgnoreCase(trimmed)) {
                printArtifacts(conversationService.listArtifacts(currentSessionId, 20), currentSessionId);
                continue;
            }
            if (trimmed.startsWith("/artifacts ")) {
                String typeFilter = trimmed.substring("/artifacts".length()).trim();
                printArtifacts(filterArtifacts(conversationService.listArtifacts(currentSessionId, 50), typeFilter),
                        currentSessionId, typeFilter);
                continue;
            }
            if (trimmed.startsWith("/artifact")) {
                String artifactId = trimmed.substring("/artifact".length()).trim();
                if (artifactId.isBlank()) {
                    out.println("Usage: /artifact <artifact-id>");
                    continue;
                }
                printArtifact(conversationService.getArtifact(artifactId));
                continue;
            }

            ConversationResponse response;
            if (trimmed.startsWith("/")) {
                printCommandProgress(trimmed);
                ConversationCommandRequest request = toCommandRequest(currentSessionId, trimmed);
                if (request == null) {
                    out.println("Unknown command. Use /help to see available commands.");
                    continue;
                }
                response = conversationService.executeCommand(request);
            } else {
                response = submitNaturalInput(conversationService, currentSessionId, trimmed, startupOptions);
            }
            printResponse(currentSessionId, response);
        }
    }

    private ConversationCommandRequest toCommandRequest(String sessionId, String line) {
        AiTaskContext context = contextParser.parse(sessionId, line);
        if (context == null || context.getCommand() == null) {
            return null;
        }
        context = mergeStartupOptions(sessionId, context, startupOptions);
        ConversationCommandRequest.ConversationCommandRequestBuilder builder = ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName(context.getCommand().getName())
                .argument(context.getCommand().getArgument());
        if (context.getAttributes() != null) {
            context.getAttributes().forEach(builder::attribute);
        }
        return builder.build();
    }

    private void printWelcome(String sessionId, ConversationService conversationService) {
        SessionSnapshot snapshot = conversationService.getSessionSnapshot(sessionId);
        out.println(heading("Consilens AI"));
        out.println("Session: " + sessionId);
        if (snapshot != null) {
            out.println("State: " + stateLine(snapshot));
            printStartupSummary(snapshot);
        }
        List<MemoryEntryDto> memories = conversationService.listMemory(sessionId, 5);
        if (!memories.isEmpty()) {
            out.println("Memory: " + memories.size() + " stored facts");
        }
        out.println(divider());
        printQuickStart();
    }

    private void printHelp() {
        out.println(heading("Help"));
        out.println(section("Core"));
        out.println("  /plan <goal>              build config from a goal");
        out.println("  /use-config <path>        load an existing YAML into this session");
        out.println("  /validate [path]          validate current or external config");
        out.println("  /dry-run [path]           validate and dry-run without executing diff");
        out.println("  /run [--approve-execute]  run the full validate/dry-run/diff/analyze loop");
        out.println("  /diff [--approve-execute] rerun diff against the current session config");
        out.println("  /diagnose [path]          diagnose latest evidence or an external diff-record");
        out.println("  /repair                   regenerate config from the latest diagnosis");
        out.println(section("Inspect"));
        out.println("  /config  /save <path>  /snapshot  /recover  /explain [path]");
        out.println("  /artifacts [type]  /artifact <id>  /memories");
        out.println(section("Session"));
        out.println("  /sessions  /resume <id>  /new");
        out.println(section("Memory"));
        out.println("  /remember <type> <content>  /forget <memory-id>");
        out.println(section("Other"));
        out.println("  /check  /analyze-last  /help  /quit");
        out.println("You can also enter a natural-language comparison goal directly.");
    }

    private void printQuickStart() {
        out.println(section("Quick start"));
        out.println("  1. Enter a comparison goal, or `/use-config <path>` to load an existing YAML.");
        out.println("  2. Use `/validate`, `/dry-run`, `/run --approve-execute`, `/repair` as needed.");
        out.println("  3. Use `/recover` after interruption, `/help` for the full command list.");
    }

    private void printStartupSummary(SessionSnapshot snapshot) {
        if (snapshot == null || snapshot.getSummary() == null || snapshot.getSummary().isBlank()) {
            return;
        }
        List<String> lines = summarize(snapshot.getSummary(), 3, 140);
        if (lines.isEmpty()) {
            return;
        }
        out.println("Last result:");
        lines.forEach(line -> out.println("  " + line));
    }

    private void printSessions(List<SessionSummaryDto> sessions, String currentSessionId) {
        if (sessions.isEmpty()) {
            out.println("No sessions found.");
            return;
        }
        out.println("# Recent Sessions");
        for (SessionSummaryDto session : sessions) {
            String marker = session.getSessionId().equals(currentSessionId) ? " *" : "";
            out.println("- " + session.getSessionId() + marker
                    + " status=" + session.getStatus()
                    + " task=" + session.getCurrentTask()
                    + " updatedAt=" + session.getLastActiveAt()
                    + titleSuffix(session));
        }
    }

    private void printCurrentConfig(CurrentConfigResponse response, String sessionId) {
        if (response == null || !response.isFound() || response.getContent() == null) {
            out.println("No current config artifact found for session " + sessionId + ".");
            return;
        }
        out.println("# Current Config (" + response.getArtifactId() + ")");
        out.println(response.getContent());
    }

    private void printMemories(List<MemoryEntryDto> memories, String sessionId) {
        if (memories.isEmpty()) {
            out.println("No memories stored for session " + sessionId + ".");
            return;
        }
        out.println("# Session Memories");
        for (MemoryEntryDto memory : memories) {
            out.println("- [" + memory.getType() + "] " + memory.getContent());
            out.println("  source=" + memory.getSource());
        }
    }

    private void printArtifacts(List<ArtifactEntryDto> artifacts, String sessionId) {
        printArtifacts(artifacts, sessionId, null);
    }

    private void printArtifacts(List<ArtifactEntryDto> artifacts, String sessionId, String typeFilter) {
        if (artifacts.isEmpty()) {
            out.println("No artifacts found for session " + sessionId + ".");
            return;
        }
        out.println(typeFilter == null || typeFilter.isBlank()
                ? "# Session Artifacts"
                : "# Session Artifacts (" + typeFilter + ")");
        for (ArtifactEntryDto artifact : artifacts) {
            out.println("- " + artifact.getArtifactId() + " [" + artifact.getType() + "] path=" + artifact.getPath()
                    + " sha=" + shortSha(artifact.getSha256()));
            if (artifact.getMetadata() != null && !artifact.getMetadata().isEmpty()) {
                out.println("  metadata=" + artifact.getMetadata());
            }
        }
    }

    private void printArtifact(ArtifactContentResponse artifact) {
        if (artifact == null || !artifact.isFound()) {
            out.println("Artifact not found.");
            return;
        }
        out.println("# Artifact " + artifact.getArtifactId() + " [" + artifact.getType() + "]");
        if (artifact.getPath() != null) {
            out.println("path=" + artifact.getPath());
        }
        if (artifact.getSha256() != null) {
            out.println("sha256=" + artifact.getSha256());
        }
        if (artifact.getMetadata() != null && !artifact.getMetadata().isEmpty()) {
            out.println("metadata=" + artifact.getMetadata());
        }
        printStreamingBlock(artifact.getContent());
    }

    private void printSnapshot(SessionSnapshot snapshot) {
        out.println("session=" + snapshot.getSessionId()
                + " config=" + snapshot.getCurrentConfigArtifactId()
                + " latestRun=" + snapshot.getLatestRunArtifactId()
                + " latestDiagnosis=" + snapshot.getLatestDiagnosisArtifactId()
                + " latestAudit=" + snapshot.getLatestAuditArtifactId());
    }

    private void printRecovery(SessionRecoveryResponse recovery) {
        if (recovery == null || recovery.getSession() == null) {
            out.println("No recovery state available.");
            return;
        }
        out.println("# Session Recovery");
        printStreamingBlock(recovery.getSummary());
        if (recovery.getLatestAudit() != null && recovery.getLatestAudit().isFound()) {
            out.println("latestAudit=" + recovery.getLatestAudit().getArtifactId());
        }
        if (recovery.getLatestDiagnosis() != null && recovery.getLatestDiagnosis().isFound()) {
            out.println("latestDiagnosis=" + recovery.getLatestDiagnosis().getArtifactId());
        }
    }

    private void printResponse(String sessionId, ConversationResponse response) {
        out.println("[AI " + responseType(response) + "] session=" + sessionId);
        if (response != null && response.getEvents() != null) {
            for (ConversationEventDto event : response.getEvents()) {
                printStreamingBlock("[AI EVENT] " + value(event.getStage())
                        + " status=" + value(event.getStatus())
                        + " artifact=" + value(event.getArtifactId())
                        + (event.getMessage() == null || event.getMessage().isBlank() ? ""
                        : " message=" + event.getMessage()));
            }
        }
        printStreamingBlock(response.getMessage());
        if (response.getSuggestedNextStep() != null) {
            printStreamingBlock("Next: " + response.getSuggestedNextStep().getCode()
                    + " - " + response.getSuggestedNextStep().getDescription());
        }
        if (response.getSession() != null) {
            printStreamingBlock("State: " + stateLine(response.getSession()));
        }
    }

    private int exitCode(ConversationResponse response) {
        switch (response.getType()) {
            case APPROVAL:
                return 2;
            case ERROR:
                return 1;
            case MESSAGE:
            case QUESTION:
            default:
                return 0;
        }
    }

    private String titleSuffix(SessionSnapshot snapshot) {
        return snapshot == null || snapshot.getSummary() == null || snapshot.getSummary().isBlank()
                ? ""
                : " summary=" + snapshot.getSummary();
    }

    private String titleSuffix(SessionSummaryDto session) {
        return session == null || session.getSummary() == null || session.getSummary().isBlank()
                ? ""
                : " summary=" + session.getSummary();
    }

    private String responseType(ConversationResponse response) {
        if (response == null || response.getType() == null) {
            return "MESSAGE";
        }
        return response.getType().name();
    }

    private String stateLine(SessionSnapshot snapshot) {
        if (snapshot == null) {
            return "(unknown)";
        }
        return "task=" + value(snapshot.getCurrentTask())
                + " status=" + value(snapshot.getStatus())
                + " config=" + value(snapshot.getCurrentConfigArtifactId())
                + " run=" + value(snapshot.getLatestRunArtifactId())
                + " diagnosis=" + value(snapshot.getLatestDiagnosisArtifactId())
                + " audit=" + value(snapshot.getLatestAuditArtifactId());
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private List<String> summarize(String message, int maxLines, int maxWidth) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        String normalized = message.replace(System.lineSeparator(), "\n");
        String[] parts = normalized.split("\\n|;\\s*");
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            lines.add(trimmed.length() > maxWidth ? trimmed.substring(0, maxWidth - 3) + "..." : trimmed);
            if (lines.size() == maxLines) {
                break;
            }
        }
        int remaining = 0;
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                remaining++;
            }
        }
        if (remaining > maxLines) {
            lines.add("... +" + (remaining - maxLines) + " more");
        }
        return lines;
    }

    private String shortSha(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private List<ArtifactEntryDto> filterArtifacts(List<ArtifactEntryDto> artifacts, String typeFilter) {
        if (artifacts == null || artifacts.isEmpty() || typeFilter == null || typeFilter.isBlank()) {
            return artifacts == null ? List.of() : artifacts;
        }
        String normalized = typeFilter.trim().toLowerCase();
        return artifacts.stream()
                .filter(artifact -> artifact.getType() != null
                        && artifact.getType().toLowerCase().contains(normalized))
                .collect(java.util.stream.Collectors.toList());
    }

    private void printCommandProgress(String command) {
        if (command.startsWith("/use-config")) {
            printStreamingBlock("[AI STAGE] load config -> validate -> bind session");
            return;
        }
        if (command.startsWith("/run")) {
            printStreamingBlock("[AI STAGE] validate -> dry-run -> approval -> diff -> analyze");
            return;
        }
        if (command.startsWith("/diff")) {
            printStreamingBlock("[AI STAGE] validate -> dry-run -> approval -> diff");
            return;
        }
        if (command.startsWith("/repair")) {
            printStreamingBlock("[AI STAGE] load failure context -> generate patch -> ready for retry");
            return;
        }
        if (command.startsWith("/validate")) {
            printStreamingBlock("[AI STAGE] validate current config");
            return;
        }
        if (command.startsWith("/dry-run") || command.startsWith("/check")) {
            printStreamingBlock("[AI STAGE] validate -> dry-run");
            return;
        }
        if (command.startsWith("/plan")) {
            printStreamingBlock("[AI STAGE] clarify -> generate config -> validate");
        }
    }

    private ConversationResponse submitNaturalInput(ConversationService conversationService,
                                                    String sessionId,
                                                    String text,
                                                    AiConsoleStartupOptions startupOptions) {
        return conversationService.sendUserTurn(sessionId, text, startupTurnAttributes(startupOptions));
    }

    private ConversationCommandRequest useConfigRequest(String sessionId, String path) {
        return ConversationCommandRequest.builder()
                .sessionId(sessionId)
                .commandName("use-config")
                .argument(path)
                .attribute(AiRuntimeContextKeys.CONFIG_PATH, path)
                .build();
    }

    private AiTaskContext mergeStartupOptions(String sessionId, AiTaskContext context, AiConsoleStartupOptions startupOptions) {
        if (startupOptions == null || !startupOptions.hasBackendHints()) {
            return context;
        }
        AiTaskContext.AiTaskContextBuilder builder = AiTaskContext.builder()
                .session(context.getSession())
                .userInput(context.getUserInput())
                .command(context.getCommand());
        if (context.getAttributes() != null) {
            context.getAttributes().forEach(builder::attribute);
        }
        String commandName = context.getCommand() == null ? null : context.getCommand().getName();
        if ("plan".equals(commandName) || "run".equals(commandName)) {
            ConfigGenerationRequest existing = context.attribute(AiRuntimeContextKeys.CONFIG_REQUEST, ConfigGenerationRequest.class);
            ConfigGenerationRequest merged = mergeBackendHints(existing == null
                    ? CompareIntentHintExtractor.enrich(sessionId,
                    context.getCommand() == null ? null : context.getCommand().getArgument(), null)
                    : existing, startupOptions);
            builder.attribute(AiRuntimeContextKeys.CONFIG_REQUEST, merged);
        }
        return builder.build();
    }

    private ConfigGenerationRequest mergeBackendHints(ConfigGenerationRequest request, AiConsoleStartupOptions startupOptions) {
        ConfigGenerationRequest.ConfigGenerationRequestBuilder builder = ConfigGenerationRequest.builder()
                .sessionId(request.getSessionId())
                .goal(request.getGoal());
        if (request.getHints() != null) {
            request.getHints().forEach(builder::hint);
        }
        addHint(builder, "backend", startupOptions.getBackend());
        addHint(builder, "model", startupOptions.getModel());
        addHint(builder, "baseUrl", startupOptions.getBaseUrl());
        addHint(builder, "apiKey", startupOptions.getApiKey());
        addHint(builder, "timeout", startupOptions.getTimeout());
        if (startupOptions.getTemperature() != null) {
            builder.hint("temperature=" + startupOptions.getTemperature());
        }
        if (startupOptions.getMaxTokens() != null) {
            builder.hint("maxTokens=" + startupOptions.getMaxTokens());
        }
        if (startupOptions.isNoLlm()) {
            builder.hint("noLlm=true");
        }
        return builder.build();
    }

    private void addHint(ConfigGenerationRequest.ConfigGenerationRequestBuilder builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.hint(key + "=" + value);
        }
    }

    private java.util.Map<String, Object> startupTurnAttributes(AiConsoleStartupOptions startupOptions) {
        java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        if (startupOptions == null || !startupOptions.hasBackendHints()) {
            return attributes;
        }
        putStartupAttribute(attributes, "backend", startupOptions.getBackend());
        putStartupAttribute(attributes, "model", startupOptions.getModel());
        putStartupAttribute(attributes, "baseUrl", startupOptions.getBaseUrl());
        putStartupAttribute(attributes, "apiKey", startupOptions.getApiKey());
        putStartupAttribute(attributes, "timeout", startupOptions.getTimeout());
        if (startupOptions.getTemperature() != null) {
            attributes.put("temperature", startupOptions.getTemperature());
        }
        if (startupOptions.getMaxTokens() != null) {
            attributes.put("maxTokens", startupOptions.getMaxTokens());
        }
        if (startupOptions.isNoLlm()) {
            attributes.put("noLlm", true);
        }
        return attributes;
    }

    private void putStartupAttribute(java.util.Map<String, Object> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    private void printStreamingBlock(String text) {
        if (text == null) {
            return;
        }
        for (String line : text.split("\\R", -1)) {
            out.println(line);
            out.flush();
        }
    }

    private String heading(String title) {
        return ANSI_BOLD + ANSI_CYAN + "──────────────── " + title + " ────────────────" + ANSI_RESET;
    }

    private String section(String title) {
        return ANSI_BOLD + title + ":" + ANSI_RESET;
    }

    private String divider() {
        return ANSI_DIM + "────────────────────────────────────────────" + ANSI_RESET;
    }

    private boolean isInteractiveMode() {
        return interactiveInput && System.console() != null;
    }
}
