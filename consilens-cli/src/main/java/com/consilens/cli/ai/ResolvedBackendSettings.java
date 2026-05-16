package com.consilens.cli.ai;

import lombok.Builder;
import lombok.Value;

/**
 * Effective backend settings after resolving CLI flags, env and persisted defaults.
 */
@Value
@Builder
public class ResolvedBackendSettings {

    String backend;
    String model;
    String baseUrl;
    String apiKey;
    String apiKeySource;
    String timeout;
    Double temperature;
    Integer maxTokens;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }
}
