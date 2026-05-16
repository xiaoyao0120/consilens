package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.cli.ai.runtime.CliAiRuntimeFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Interactive shell alias for the root `consilens ai` REPL.
 */
@Command(
    name = "shell",
    description = "Start the interactive Consilens AI REPL (alias of `consilens ai`)",
    mixinStandardHelpOptions = true
)
public class AiShellCommand implements Callable<Integer> {

    private final Supplier<ConversationService> conversationServiceSupplier;

    @Option(names = "--session", description = "AI session ID to reuse")
    private String sessionId;

    @Option(names = {"-c", "--config"}, description = "Load an existing config file into the session before entering the shell")
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

    public AiShellCommand() {
        this(() -> new CliAiRuntimeFactory().createConversationService());
    }

    AiShellCommand(Supplier<ConversationService> conversationServiceSupplier) {
        this.conversationServiceSupplier = conversationServiceSupplier;
    }

    @Override
    public Integer call() throws Exception {
        AiConsoleStartupOptions startupOptions = AiConsoleStartupOptions.builder()
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
        String validationError = AiStartupValidator.validate(startupOptions);
        if (validationError != null) {
            System.out.println("[AI ERROR] " + validationError);
            return 1;
        }
        return new AiConsoleLoop(conversationServiceSupplier)
                .start(sessionId, false, null, startupOptions);
    }
}
