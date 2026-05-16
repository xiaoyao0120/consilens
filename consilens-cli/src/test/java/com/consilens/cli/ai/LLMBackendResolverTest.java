package com.consilens.cli.ai;

import com.consilens.ai.spi.LLMBackend;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LLMBackendResolverTest {

    private final LLMBackendResolver resolver = new LLMBackendResolver();

    @Test
    void shouldResolveNoopByDefault() {
        LLMBackend backend = resolver.resolve(AIBackendOptions.builder().build());

        assertEquals("noop", backend.info().getName());
    }

    @Test
    void shouldResolveOpenAIWithExplicitOptions() {
        LLMBackend backend = resolver.resolve(AIBackendOptions.builder()
                .backend("openai")
                .model("test-model")
                .baseUrl("https://example.invalid/v1")
                .apiKey("test-key")
                .build());

        assertEquals("openai", backend.info().getName());
        assertEquals("test-model", backend.info().getModel());
    }

    @Test
    void shouldResolveDeepSeekWithExplicitOptions() {
        LLMBackend backend = resolver.resolve(AIBackendOptions.builder()
                .backend("deepseek")
                .model("deepseek-test")
                .baseUrl("https://example.invalid")
                .apiKey("test-key")
                .build());

        assertEquals("deepseek", backend.info().getName());
        assertEquals("deepseek-test", backend.info().getModel());
    }

    @Test
    void shouldResolveOllamaWithExplicitOptions() {
        LLMBackend backend = resolver.resolve(AIBackendOptions.builder()
                .backend("ollama")
                .model("qwen-test")
                .baseUrl("http://localhost:11434")
                .timeout("5s")
                .temperature(0.2)
                .maxTokens(128)
                .build());

        assertEquals("ollama", backend.info().getName());
        assertEquals("qwen-test", backend.info().getModel());
    }

    @Test
    void shouldRejectUnknownBackend() {
        AIBackendOptions options = AIBackendOptions.builder().backend("missing-backend").build();

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(options));
    }

    @Test
    void shouldResolveBackendNameFromEnvironmentWhenOptionMissing() {
        Map<String, String> env = Map.of("CONSILENS_AI_BACKEND", "openai");
        LLMBackendResolver envResolver = new LLMBackendResolver(env::get);

        String backend = envResolver.resolveBackendName(AIBackendOptions.builder().backend(null).build());

        assertEquals("openai", backend);
    }

    @Test
    void shouldResolveBackendNameAndSettingsFromDefaultsFile() {
        AiBackendDefaults defaults = AiBackendDefaults.builder()
                .defaultBackend("openai")
                .shared(AiBackendDefaults.BackendDefaults.builder()
                        .timeout("20s")
                        .build())
                .backends(Map.of("openai", AiBackendDefaults.BackendDefaults.builder()
                        .model("gpt-4.1-mini")
                        .baseUrl("https://api.openai.com/v1")
                        .apiKeyEnv("TEAM_OPENAI_KEY")
                        .build()))
                .build();
        Map<String, String> env = Map.of("TEAM_OPENAI_KEY", "team-key");
        LLMBackendResolver envResolver = new LLMBackendResolver(env::get, () -> Optional.of(defaults));

        ResolvedBackendSettings settings = envResolver.resolveSettings(AIBackendOptions.builder().backend(null).build());

        assertEquals("openai", settings.getBackend());
        assertEquals("gpt-4.1-mini", settings.getModel());
        assertEquals("https://api.openai.com/v1", settings.getBaseUrl());
        assertEquals("team-key", settings.getApiKey());
        assertEquals("TEAM_OPENAI_KEY", settings.getApiKeySource());
        assertEquals("20s", settings.getTimeout());
    }
}
