package com.consilens.server.application.ai;

import com.consilens.agent.core.loop.AgentRunConfig;
import com.consilens.agent.model.openai.OpenAiCompatibleClientConfig;
import com.consilens.agent.model.openai.OpenAiCompatibleModelClient;
import com.consilens.server.boot.ConsilensServerProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds provider transport and loop configuration from controlled server
 * properties. Keys are resolved from environment variable names; the values
 * never enter agent state, events or logs.
 */
@Component
public class AgentRuntimeFactory {

    private final ConsilensServerProperties properties;

    public AgentRuntimeFactory(ConsilensServerProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.getAi().isEnabled();
    }

    public OpenAiCompatibleModelClient createModelClient() {
        ConsilensServerProperties.Ai ai = properties.getAi();
        String apiKey = firstNonBlank(ai.getApiKey(), System.getenv(ai.getApiKeyEnv()));
        OpenAiCompatibleClientConfig config = OpenAiCompatibleClientConfig.builder()
                .baseUrl(ai.getBaseUrl())
                .apiKey(apiKey == null ? "" : apiKey)
                .model(ai.getModel())
                .stream(false)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(ai.getRunTimeoutSeconds()))
                .maxRetries(2)
                .build();
        return new OpenAiCompatibleModelClient(config);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    public AgentRunConfig createRunConfig() {
        ConsilensServerProperties.Ai ai = properties.getAi();
        return AgentRunConfig.builder()
                .maxTurns(ai.getMaxTurns())
                .maxToolCalls(ai.getMaxToolCalls())
                .runTimeout(Duration.ofSeconds(ai.getRunTimeoutSeconds()))
                .parallelReadsEnabled(false)
                .build();
    }
}
