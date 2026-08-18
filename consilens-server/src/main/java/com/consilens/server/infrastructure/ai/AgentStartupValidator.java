package com.consilens.server.infrastructure.ai;

import com.consilens.server.boot.ConsilensServerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup gate for AI runtime (design section 30): provider discoverable,
 * model/baseUrl valid, API key env present, secret store key present and the
 * AI tables accessible. With fail-fast the server refuses to start instead of
 * silently degrading to plaintext or noop behavior.
 */
@Slf4j
public class AgentStartupValidator implements ApplicationRunner {

    private final ConsilensServerProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public AgentStartupValidator(ConsilensServerProperties properties,
                                 JdbcTemplate jdbcTemplate,
                                 Environment environment) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        ConsilensServerProperties.Ai ai = properties.getAi();
        List<String> problems = new ArrayList<>();

        if (!List.of("deepseek", "openai", "ollama").contains(ai.getBackend())) {
            problems.add("unsupported ai.backend: " + ai.getBackend());
        }
        if (blank(ai.getBaseUrl())) {
            problems.add("ai.base-url is required");
        }
        if (blank(ai.getModel())) {
            problems.add("ai.model is required");
        }
        String apiKey = firstNonBlank(ai.getApiKey(), System.getenv(ai.getApiKeyEnv()));
        if (blank(apiKey)) {
            problems.add("api key env " + ai.getApiKeyEnv() + " is not set");
        }
        String datasourceKey = firstNonBlank(
                environment.getProperty("consilens.server.datasource.encryption-key"),
                System.getenv("CONSILENS_DATASOURCE_ENCRYPTION_KEY"));
        if (blank(datasourceKey)) {
            problems.add("consilens.server.datasource.encryption-key is not set");
        }
        String secretKey = firstNonBlank(ai.getSecretKey(), System.getenv(ai.getSecretKeyEnv()));
        if ("encrypted-db".equals(ai.getSecretStore()) && blank(secretKey)) {
            problems.add("secret key env " + ai.getSecretKeyEnv() + " is not set");
        }
        try {
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cs_ai_session", Integer.class);
        } catch (Exception e) {
            problems.add("AI tables are not accessible: " + e.getClass().getSimpleName());
        }

        if (problems.isEmpty()) {
            log.info("AI agent runtime startup validation passed (backend={}, model={})",
                    ai.getBackend(), ai.getModel());
            return;
        }
        if (ai.isFailFast()) {
            throw new IllegalStateException("AI agent runtime startup failed: " + String.join("; ", problems));
        }
        log.error("AI agent runtime unavailable (fail-fast disabled): {}", String.join("; ", problems));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
