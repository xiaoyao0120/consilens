package com.consilens.cli.ai;

import com.consilens.ai.spi.LLMBackend;
import com.consilens.ai.spi.LLMBackendManager;
import com.consilens.cli.ai.runtime.AiRuntimePaths;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves an LLM backend from CLI options and environment defaults.
 */
public class LLMBackendResolver {

    static final String DEFAULT_BACKEND = "noop";
    static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    static final String OLLAMA_BASE_URL_ENV = "OLLAMA_BASE_URL";
    static final String OPENAI_API_KEY_ENV = "OPENAI_API_KEY";
    static final String DEEPSEEK_API_KEY_ENV = "DEEPSEEK_API_KEY";
    static final String API_KEY_ENV_OVERRIDE_ENV = "CONSILENS_AI_API_KEY_ENV";
    static final String BACKEND_DEFAULTS_SOURCE = "backend-defaults.json";

    private final Function<String, String> envProvider;
    private final Supplier<Optional<AiBackendDefaults>> backendDefaultsProvider;

    public LLMBackendResolver() {
        this(System::getenv, new AiBackendDefaultsStore(new AiRuntimePaths())::load);
    }

    public LLMBackendResolver(Function<String, String> envProvider) {
        this(envProvider, () -> Optional.empty());
    }

    LLMBackendResolver(Function<String, String> envProvider,
                       Supplier<Optional<AiBackendDefaults>> backendDefaultsProvider) {
        this.envProvider = envProvider;
        this.backendDefaultsProvider = backendDefaultsProvider;
    }

    public LLMBackend resolve(AIBackendOptions options) {
        ResolvedBackendSettings effective = resolveSettings(options);
        Map<String, Object> config = new LinkedHashMap<>();
        put(config, "model", effective.getModel());
        put(config, "baseUrl", effective.getBaseUrl());
        put(config, "apiKey", effective.getApiKey());
        put(config, "timeout", effective.getTimeout());
        put(config, "temperature", effective.getTemperature());
        put(config, "maxTokens", effective.getMaxTokens());
        try {
            return LLMBackendManager.getInstance().create(effective.getBackend(), config);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown or unavailable AI backend: " + effective.getBackend(), e);
        }
    }

    public String resolveBackendName(AIBackendOptions options) {
        return resolveSettings(options).getBackend();
    }

    public ResolvedBackendSettings resolveSettings(AIBackendOptions options) {
        AIBackendOptions effective = options == null ? AIBackendOptions.builder().backend(null).build() : options;
        AiBackendDefaults defaults = backendDefaultsProvider.get().orElse(null);
        AiBackendDefaults.BackendDefaults sharedDefaults = defaults == null ? null : defaults.getShared();
        String backend = firstNonBlank(
                effective.getBackend(),
                env("CONSILENS_AI_BACKEND"),
                defaults == null ? null : defaults.getDefaultBackend(),
                DEFAULT_BACKEND);
        AiBackendDefaults.BackendDefaults backendDefaults = defaults == null ? null : defaults.defaultsFor(backend);

        String configuredApiKeyEnv = firstNonBlank(
                value(backendDefaults == null ? null : backendDefaults.getApiKeyEnv()),
                value(sharedDefaults == null ? null : sharedDefaults.getApiKeyEnv()),
                apiKeyEnvName(backend));
        String envApiKey = configuredApiKeyEnv == null ? null : env(configuredApiKeyEnv);
        String profileApiKey = firstNonBlank(
                value(backendDefaults == null ? null : backendDefaults.getApiKey()),
                value(sharedDefaults == null ? null : sharedDefaults.getApiKey()));

        return ResolvedBackendSettings.builder()
                .backend(backend)
                .model(firstNonBlank(
                        effective.getModel(),
                        env("CONSILENS_AI_MODEL"),
                        value(backendDefaults == null ? null : backendDefaults.getModel()),
                        value(sharedDefaults == null ? null : sharedDefaults.getModel())))
                .baseUrl(firstNonBlank(
                        effective.getBaseUrl(),
                        env("CONSILENS_AI_BASE_URL"),
                        value(backendDefaults == null ? null : backendDefaults.getBaseUrl()),
                        value(sharedDefaults == null ? null : sharedDefaults.getBaseUrl()),
                        backendDefaultBaseUrl(backend)))
                .apiKey(firstNonBlank(effective.getApiKey(), envApiKey, profileApiKey))
                .apiKeySource(apiKeySource(effective.getApiKey(), configuredApiKeyEnv, envApiKey, profileApiKey))
                .timeout(firstNonBlank(
                        effective.getTimeout(),
                        env("CONSILENS_AI_TIMEOUT"),
                        value(backendDefaults == null ? null : backendDefaults.getTimeout()),
                        value(sharedDefaults == null ? null : sharedDefaults.getTimeout())))
                .temperature(firstNonNull(
                        effective.getTemperature(),
                        doubleEnv("CONSILENS_AI_TEMPERATURE"),
                        backendDefaults == null ? null : backendDefaults.getTemperature(),
                        sharedDefaults == null ? null : sharedDefaults.getTemperature()))
                .maxTokens(firstNonNull(
                        effective.getMaxTokens(),
                        integerEnv("CONSILENS_AI_MAX_TOKENS"),
                        backendDefaults == null ? null : backendDefaults.getMaxTokens(),
                        sharedDefaults == null ? null : sharedDefaults.getMaxTokens()))
                .build();
    }

    private String backendDefaultBaseUrl(String backend) {
        if ("ollama".equalsIgnoreCase(backend)) {
            return firstNonBlank(env(OLLAMA_BASE_URL_ENV), DEFAULT_OLLAMA_BASE_URL);
        }
        return null;
    }

    private String apiKeyEnvName(String backend) {
        String override = firstNonBlank(env(API_KEY_ENV_OVERRIDE_ENV));
        if (override != null) {
            return override;
        }
        if ("openai".equalsIgnoreCase(backend)) {
            return OPENAI_API_KEY_ENV;
        }
        if ("deepseek".equalsIgnoreCase(backend)) {
            return DEEPSEEK_API_KEY_ENV;
        }
        return null;
    }

    private void put(Map<String, Object> config, String key, Object value) {
        if (value != null && !String.valueOf(value).trim().isEmpty()) {
            config.put(key, value);
        }
    }

    private String env(String name) {
        return envProvider.apply(name);
    }

    private String value(String value) {
        return value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Double doubleEnv(String name) {
        String value = env(name);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer integerEnv(String name) {
        String value = env(name);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String apiKeySource(String explicitApiKey, String envName, String envApiKey, String profileApiKey) {
        if (explicitApiKey != null && !explicitApiKey.trim().isEmpty()) {
            return "--api-key";
        }
        if (envApiKey != null && !envApiKey.trim().isEmpty()) {
            return envName;
        }
        if (profileApiKey != null && !profileApiKey.trim().isEmpty()) {
            return BACKEND_DEFAULTS_SOURCE;
        }
        return null;
    }
}
