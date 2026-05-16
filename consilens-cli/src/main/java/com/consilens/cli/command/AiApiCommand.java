package com.consilens.cli.command;

import com.consilens.ai.conversation.api.ConversationService;
import com.consilens.cli.ai.runtime.CliAiRuntimeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * Starts an HTTP adapter for the high-level AI conversation API.
 */
@Command(
    name = "api",
    description = "Start HTTP API adapter for Consilens AI conversation service",
    mixinStandardHelpOptions = true
)
public class AiApiCommand implements Callable<Integer> {

    private final Supplier<ConversationService> conversationServiceSupplier;

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Host to bind")
    private String host;

    @Option(names = "--port", defaultValue = "8088", description = "Port to bind")
    private int port;

    @Option(names = "--auth-token", description = "Bearer token required by the HTTP API")
    private String authToken;

    @Option(names = "--auth-token-env", defaultValue = "CONSILENS_AI_API_TOKEN",
            description = "Environment variable used when --auth-token is omitted")
    private String authTokenEnv;

    public AiApiCommand() {
        this(() -> new CliAiRuntimeFactory().createConversationService());
    }

    AiApiCommand(Supplier<ConversationService> conversationServiceSupplier) {
        this.conversationServiceSupplier = conversationServiceSupplier;
    }

    @Override
    public Integer call() throws Exception {
        ConversationService service = conversationServiceSupplier.get();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String effectiveAuthToken = authToken;
        if ((effectiveAuthToken == null || effectiveAuthToken.isBlank())
                && authTokenEnv != null && !authTokenEnv.isBlank()) {
            effectiveAuthToken = System.getenv(authTokenEnv);
        }
        AiApiServer.RunningServer server = new AiApiServer(service, mapper, effectiveAuthToken).start(host, port);
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            latch.countDown();
        }));
        System.out.println("AI conversation API started at http://" + host + ":" + server.port());
        if (effectiveAuthToken != null && !effectiveAuthToken.isBlank()) {
            System.out.println("Authorization: Bearer token required");
        }
        System.out.println("Press Ctrl+C to stop.");
        latch.await();
        return 0;
    }
}
