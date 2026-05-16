package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.cli.ai.runtime.CliAiRuntimeFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * AI-assisted Consilens commands.
 */
@Command(
    name = "ai",
    description = "AI runtime commands for plan/run/repair/shell closed-loop workflows",
    mixinStandardHelpOptions = true,
    subcommands = {
        AiPlanCommand.class,
        AiRunCommand.class,
        AiRepairCommand.class,
        AiShellCommand.class,
        AiMemoriesCommand.class,
        AiConfigCommand.class,
        AiExplainCommand.class,
        AiDiagnoseCommand.class,
        AiDiffCommand.class,
        AiProvidersCommand.class,
        AiDoctorCommand.class,
        AiApiCommand.class
    }
)
public class AiCommand implements Callable<Integer> {

    private final Supplier<ConversationService> conversationServiceSupplier;

    @Parameters(arity = "0..*", description = "Natural language request for single-shot mode")
    private List<String> request;

    @Option(names = {"--session", "--resume"}, description = "Session ID to reuse")
    private String sessionId;

    @Option(names = "--new", description = "Start a fresh session")
    private boolean newSession;

    @Option(names = {"-c", "--config"}, description = "Load an existing config file into the session before entering AI mode")
    private String configPath;

    @Option(names = "--backend", description = "AI backend to use for startup planning")
    private String backend;

    @Option(names = "--model", description = "AI model name")
    private String model;

    @Option(names = "--base-url", description = "AI backend base URL")
    private String baseUrl;

    @Option(names = "--api-key", description = "AI backend API key")
    private String apiKey;

    @Option(names = "--timeout", description = "AI backend timeout")
    private String timeout;

    @Option(names = "--temperature", description = "AI sampling temperature")
    private Double temperature;

    @Option(names = "--max-tokens", description = "AI max output tokens")
    private Integer maxTokens;

    @Option(names = "--no-llm", description = "Do not call an LLM for startup planning")
    private boolean noLlm;

    public AiCommand() {
        this(() -> new CliAiRuntimeFactory().createConversationService());
    }

    AiCommand(Supplier<ConversationService> conversationServiceSupplier) {
        this.conversationServiceSupplier = conversationServiceSupplier;
    }

    @Override
    public Integer call() throws Exception {
        String initialRequest = request == null || request.isEmpty() ? null : String.join(" ", request).trim();
        AiConsoleStartupOptions startupOptions = startupOptions();
        String validationError = AiStartupValidator.validate(startupOptions);
        if (validationError != null) {
            System.out.println("[AI ERROR] " + validationError);
            return 1;
        }
        return new AiConsoleLoop(conversationServiceSupplier)
                .start(sessionId, newSession, initialRequest, startupOptions);
    }

    private AiConsoleStartupOptions startupOptions() {
        return AiConsoleStartupOptions.builder()
                .configPath(configPath)
                .backend(backend)
                .model(model)
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(timeout)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .noLlm(noLlm)
                .build();
    }
}
