package com.consilens.cli.ai;

import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.spi.LLMBackend;
import com.consilens.cli.model.CliConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIConfigServiceTest {

    @Test
    void shouldGenerateConfigFromLlmYamlWhenHintsAreMissing() {
        String yaml = "source:\n"
                + "  type: mysql\n"
                + "  name: source-mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/source\n"
                + "    username: ${env.MYSQL_USER}\n"
                + "    password: ${env.MYSQL_PASSWORD}\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "target:\n"
                + "  type: postgresql\n"
                + "  name: target-postgresql\n"
                + "  connection:\n"
                + "    url: jdbc:postgresql://localhost:5432/target\n"
                + "    username: ${env.PG_USER}\n"
                + "    password: ${env.PG_PASSWORD}\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: users\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source:\n"
                + "      - id\n"
                + "    target:\n"
                + "      - id\n"
                + "strategy:\n"
                + "  mode: checksum\n"
                + "result:\n"
                + "  sinks:\n"
                + "    - format: console\n"
                + "      type: result\n";
        AIConfigService service = serviceReturning(yaml);

        AIConfigResult result = service.generate(AIConfigRequest.builder()
                .goal("compare users")
                .backendOptions(AIBackendOptions.builder().backend("openai").build())
                .build());

        assertTrue(result.isValid());
        CliConfiguration config = result.getConfiguration();
        assertEquals("mysql", config.getSource().getType());
        assertEquals("postgresql", config.getTarget().getType());
        assertEquals("id", config.getComparison().getKeys().getSource().get(0));
        assertTrue(result.getYaml().contains("comparison:"));
    }

    @Test
    void shouldRejectInvalidLlmJson() {
        AIConfigService service = serviceReturning("not json");

        AIConfigRequest request = AIConfigRequest.builder()
                .goal("compare users")
                .backendOptions(AIBackendOptions.builder().backend("openai").build())
                .build();

        assertThrows(IllegalArgumentException.class, () -> service.generate(request));
    }

    @Test
    void shouldSurfaceActionableBackendFailure() {
        AIConfigService service = new AIConfigService(
                new com.consilens.ai.config.AIConfigDraftValidator(),
                new AIConfigCompiler(),
                new FailingBackendResolver(),
                new com.consilens.ai.conversation.engine.ExampleTemplateStore());

        AIConfigRequest request = AIConfigRequest.builder()
                .goal("compare users")
                .backendOptions(AIBackendOptions.builder().backend("openai").build())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.generate(request));
        assertTrue(error.getMessage().contains("consilens ai doctor"));
        assertTrue(error.getMessage().contains("backend-defaults.json"));
    }

    @Test
    void shouldNotCallNoopLlmWhenBackendOptionIsMissing() {
        AIConfigService service = new AIConfigService(
                new com.consilens.ai.config.AIConfigDraftValidator(),
                new AIConfigCompiler(),
                new StaticBackendResolver(new StaticBackend("not json")),
                new com.consilens.ai.conversation.engine.ExampleTemplateStore());

        AIConfigResult result = service.generate(AIConfigRequest.builder()
                .goal("compare users")
                .backendOptions(AIBackendOptions.builder().backend(null).build())
                .build());

        assertEquals(false, result.isValid());
        assertTrue(result.getIssues().stream()
                .anyMatch(issue -> "AI_CONFIG_DATASET_TYPE_MISSING".equals(issue.getCode())));
    }

    private AIConfigService serviceReturning(String response) {
        return new AIConfigService(
                new com.consilens.ai.config.AIConfigDraftValidator(),
                new AIConfigCompiler(),
                new StaticBackendResolver(new StaticBackend(response)),
                new com.consilens.ai.conversation.engine.ExampleTemplateStore());
    }

    private static class StaticBackendResolver extends LLMBackendResolver {
        private final LLMBackend backend;

        StaticBackendResolver(LLMBackend backend) {
            this.backend = backend;
        }

        @Override
        public LLMBackend resolve(AIBackendOptions options) {
            return backend;
        }
    }

    private static class FailingBackendResolver extends LLMBackendResolver {
        @Override
        public LLMBackend resolve(AIBackendOptions options) {
            throw new IllegalStateException("401 unauthorized");
        }

        @Override
        public ResolvedBackendSettings resolveSettings(AIBackendOptions options) {
            return ResolvedBackendSettings.builder().backend("openai").build();
        }
    }

    private static class StaticBackend implements LLMBackend {
        private final String response;

        StaticBackend(String response) {
            this.response = response;
        }

        @Override
        public LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions) {
            return LLMResponse.builder().text(response).finishReason("stop").build();
        }

        @Override
        public String complete(String prompt) {
            return response;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public BackendInfo info() {
            return BackendInfo.builder()
                    .name("static")
                    .model("test")
                    .version("test")
                    .supportsFunctionCalling(false)
                    .supportsStreaming(false)
                    .build();
        }
    }
}
