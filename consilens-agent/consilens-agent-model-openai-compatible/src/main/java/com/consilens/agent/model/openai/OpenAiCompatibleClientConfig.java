package com.consilens.agent.model.openai;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * Provider transport config. API keys must be resolved from environment
 * references by the caller; they never enter agent state or events.
 */
@Value
@Builder
public class OpenAiCompatibleClientConfig {
    String baseUrl;
    String apiKey;
    String model;
    boolean stream;
    Duration connectTimeout;
    Duration readTimeout;
    int maxRetries;

    public static OpenAiCompatibleClientConfig defaults() {
        return OpenAiCompatibleClientConfig.builder()
                .stream(false)
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .maxRetries(2)
                .build();
    }
}
