package com.consilens.cli.command;

import com.consilens.cli.ai.AIBackendOptions;
import com.consilens.cli.ai.LLMBackendResolver;
import com.consilens.cli.ai.ResolvedBackendSettings;

/**
 * Validates backend requirements before entering the interactive AI shell.
 */
final class AiStartupValidator {

    private AiStartupValidator() {
    }

    static String validate(AiConsoleStartupOptions options) {
        if (options == null || options.isNoLlm()) {
            return null;
        }
        AIBackendOptions backendOptions = AIBackendOptions.builder()
                .backend(options.getBackend())
                .model(options.getModel())
                .baseUrl(options.getBaseUrl())
                .apiKey(options.getApiKey())
                .timeout(options.getTimeout())
                .temperature(options.getTemperature())
                .maxTokens(options.getMaxTokens())
                .noLlm(false)
                .build();
        LLMBackendResolver resolver = new LLMBackendResolver();
        ResolvedBackendSettings settings;
        try {
            settings = resolver.resolveSettings(backendOptions);
            resolver.resolve(backendOptions);
        } catch (RuntimeException e) {
            return "Backend config is invalid: " + e.getMessage();
        }
        if (settings.getBackend() == null
                || settings.getBackend().isBlank()
                || "noop".equalsIgnoreCase(settings.getBackend())) {
            return "Backend is required. Pass --backend or configure backend-defaults.json.";
        }
        if (settings.getBaseUrl() == null || settings.getBaseUrl().isBlank()) {
            return "Base URL is required. Pass --base-url or configure backend-defaults.json.";
        }
        if (!hasText(options.getApiKey()) && !settings.hasApiKey()) {
            return "API key is required. Pass --api-key or configure backend-defaults.json/apiKeyEnv.";
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
