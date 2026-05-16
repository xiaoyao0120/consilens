package com.consilens.cli.command;

import lombok.Builder;
import lombok.Value;

/**
 * Startup options applied to the interactive AI shell.
 */
@Value
@Builder
class AiConsoleStartupOptions {

    String configPath;
    String backend;
    String model;
    String baseUrl;
    String apiKey;
    String timeout;
    Double temperature;
    Integer maxTokens;
    boolean noLlm;

    boolean hasConfigPath() {
        return configPath != null && !configPath.trim().isEmpty();
    }

    boolean hasBackendHints() {
        return hasText(backend)
                || hasText(model)
                || hasText(baseUrl)
                || hasText(apiKey)
                || hasText(timeout)
                || temperature != null
                || maxTokens != null
                || noLlm;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
