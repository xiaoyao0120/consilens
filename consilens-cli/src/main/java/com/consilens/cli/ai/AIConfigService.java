package com.consilens.cli.ai;

import com.consilens.ai.config.AIConfigDraftValidator;
import com.consilens.ai.config.model.AIConfigDraft;
import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.ai.config.model.DatasetDraft;
import com.consilens.ai.config.model.MappingDraft;
import com.consilens.ai.config.model.ResultDraft;
import com.consilens.ai.config.model.StrategyDraft;
import com.consilens.ai.conversation.engine.ExampleTemplate;
import com.consilens.ai.conversation.engine.ExampleTemplateStore;
import com.consilens.ai.spi.LLMBackend;
import com.consilens.cli.model.CliConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generates a validated Consilens CLI configuration from explicit hints and optional LLM output.
 * When an {@link ExampleTemplateStore} is available, the best-matching example is used as a
 * template in the LLM prompt so that generated configs follow the correct YAML schema.
 */
public class AIConfigService {

    private final AIConfigDraftValidator validator;
    private final AIConfigCompiler compiler;
    private final LLMBackendResolver backendResolver;
    private final ObjectMapper yamlMapper;
    private final ExampleTemplateStore exampleTemplateStore;

    public AIConfigService() {
        this(new AIConfigDraftValidator(), new AIConfigCompiler(), new LLMBackendResolver(),
                new ExampleTemplateStore());
    }

    public AIConfigService(ExampleTemplateStore exampleTemplateStore) {
        this(new AIConfigDraftValidator(), new AIConfigCompiler(), new LLMBackendResolver(),
                exampleTemplateStore);
    }

    AIConfigService(AIConfigDraftValidator validator,
                    AIConfigCompiler compiler,
                    LLMBackendResolver backendResolver,
                    ObjectMapper objectMapper) {
        this(validator, compiler, backendResolver, (ExampleTemplateStore) null);
    }

    AIConfigService(AIConfigDraftValidator validator,
                    AIConfigCompiler compiler,
                    LLMBackendResolver backendResolver,
                    ExampleTemplateStore exampleTemplateStore) {
        this.validator = validator;
        this.compiler = compiler;
        this.backendResolver = backendResolver;
        this.exampleTemplateStore = exampleTemplateStore;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    public AIConfigResult generate(AIConfigRequest request) {
        AIConfigDraft draft = buildDraftFromRequest(request);
        List<AIConfigIssue> issues = validator.validate(draft);

        AIBackendOptions backendOptions = request.getBackendOptions();
        String backendName = backendResolver.resolveBackendName(backendOptions);
        if (validator.hasErrors(issues)
                && backendOptions != null
                && !backendOptions.isNoLlm()
                && !"noop".equalsIgnoreCase(backendName)) {
            Optional<AIConfigResult> yamlResult = tryGenerateFromExample(request);
            if (yamlResult.isPresent()) {
                return yamlResult.get();
            }
            throw new IllegalArgumentException("AI config generation failed: no example template could be adapted. "
                    + "Check your backend settings with `consilens ai doctor`, or provide more specific source/target details.");
        }

        if (validator.hasErrors(issues)) {
            return AIConfigResult.builder()
                    .draft(draft)
                    .issues(issues)
                    .valid(false)
                    .dryRunPassed(false)
                    .build();
        }

        CliConfiguration configuration = compiler.compile(draft);
        try {
            configuration.validate();
        } catch (Exception e) {
            issues = List.of(AIConfigIssue.builder()
                    .severity(AIConfigIssue.Severity.ERROR)
                    .path("configuration")
                    .code("AI_CONFIG_COMPILED_CONFIG_INVALID")
                    .message(e.getMessage())
                    .build());
            return AIConfigResult.builder()
                    .draft(draft)
                    .configuration(configuration)
                    .issues(issues)
                    .valid(false)
                    .dryRunPassed(false)
                    .build();
        }

        return AIConfigResult.builder()
                .draft(draft)
                .configuration(configuration)
                .yaml(compiler.toYaml(configuration))
                .issues(issues)
                .valid(true)
                .dryRunPassed(false)
                .build();
    }

    /**
     * Tries to generate a config by asking the LLM to adapt the best-matching example template.
     * Returns empty only if no example store is available or if YAML cannot be parsed at all.
     * Even if the generated config fails validation, the parsed draft is returned (valid=false + yaml)
     * so callers can display it as a template for the user to fill in.
     */
    private Optional<AIConfigResult> tryGenerateFromExample(AIConfigRequest request) {
        if (exampleTemplateStore == null || exampleTemplateStore.isEmpty()) {
            return Optional.empty();
        }
        String searchQuery = buildSearchQuery(request);
        Optional<ExampleTemplate> example = exampleTemplateStore.findBestMatch(searchQuery);
        if (example.isEmpty()) {
            return Optional.empty();
        }
        LLMBackend backend;
        String yaml;
        try {
            backend = backendResolver.resolve(request.getBackendOptions());
            String response = backend.complete(buildYamlPrompt(request, example.get()));
            yaml = extractYaml(response);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI backend failed. Check `consilens ai doctor`, backend-defaults.json, API key and base URL. Root cause: "
                    + e.getMessage(), e);
        }
        CliConfiguration configuration;
        try {
            configuration = yamlMapper.readValue(yaml, CliConfiguration.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("AI backend returned YAML that could not be parsed as a valid Consilens configuration. "
                    + "Root cause: " + e.getMessage(), e);
        }
        // Attempt validation; return draft even on failure so user can see and edit the template
        List<AIConfigIssue> issues;
        boolean valid;
        try {
            configuration.validate();
            issues = List.of();
            valid = true;
        } catch (Exception e) {
            issues = List.of(AIConfigIssue.builder()
                    .severity(AIConfigIssue.Severity.WARNING)
                    .path("configuration")
                    .code("AI_CONFIG_TEMPLATE_DRAFT")
                    .message("Draft template generated from example '" + example.get().getName()
                            + "'. Please fill in connection URLs and credential env variables: " + e.getMessage())
                    .build());
            valid = false;
        }
        return Optional.of(AIConfigResult.builder()
                .configuration(configuration)
                .yaml(yaml)
                .issues(issues)
                .valid(valid)
                .dryRunPassed(false)
                .build());
    }

    private String buildSearchQuery(AIConfigRequest request) {
        StringBuilder sb = new StringBuilder();
        if (request.getGoal() != null) sb.append(request.getGoal()).append(" ");
        if (request.getSourceType() != null) sb.append(request.getSourceType()).append(" ");
        if (request.getTargetType() != null) sb.append(request.getTargetType()).append(" ");
        if (request.getSourceQuery() != null || request.getTargetQuery() != null) sb.append("sql ");
        return sb.toString().trim();
    }

    private String buildYamlPrompt(AIConfigRequest request, ExampleTemplate example) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a Consilens configuration generator.\n");
        sb.append("Generate a Consilens YAML configuration to compare data between two sources.\n");
        sb.append("Output ONLY valid YAML. No markdown code blocks. No explanation.\n");
        sb.append("Use ${env.VAR_NAME} for all credentials (never hardcode passwords).\n");
        sb.append("\n");
        sb.append("Use the following example as a template and adapt it to match the user's goal:\n");
        sb.append("--- Example: ").append(example.getName()).append(" ---\n");
        sb.append(example.getContent().trim()).append("\n");
        sb.append("--- End of example ---\n");
        sb.append("\n");
        sb.append("User's comparison goal:\n");
        sb.append(nullToEmpty(request.getGoal())).append("\n");
        if (request.getSourceType() != null) sb.append("Source type: ").append(request.getSourceType()).append("\n");
        if (request.getSourceUrl() != null) sb.append("Source URL: ").append(request.getSourceUrl()).append("\n");
        if (request.getSourceTable() != null) sb.append("Source table: ").append(request.getSourceTable()).append("\n");
        if (request.getSourceQuery() != null) sb.append("Source SQL: ").append(request.getSourceQuery()).append("\n");
        if (request.getTargetType() != null) sb.append("Target type: ").append(request.getTargetType()).append("\n");
        if (request.getTargetUrl() != null) sb.append("Target URL: ").append(request.getTargetUrl()).append("\n");
        if (request.getTargetTable() != null) sb.append("Target table: ").append(request.getTargetTable()).append("\n");
        if (request.getTargetQuery() != null) sb.append("Target SQL: ").append(request.getTargetQuery()).append("\n");
        if (request.getKeys() != null) sb.append("Compare keys: ").append(request.getKeys()).append("\n");
        if (request.getSourceKeys() != null) sb.append("Source keys: ").append(request.getSourceKeys()).append("\n");
        if (request.getTargetKeys() != null) sb.append("Target keys: ").append(request.getTargetKeys()).append("\n");
        sb.append("\n");
        sb.append("Adapt the example YAML to match this goal. ");
        sb.append("Fill in known values, keep ${env.VAR_NAME} placeholders for credentials.\n");
        sb.append("Output ONLY the YAML, nothing else.");
        return sb.toString();
    }


    /** Strips optional markdown code fences (```yaml ... ```) from LLM YAML output. */
    private String extractYaml(String response) {
        if (response == null) {
            return "";
        }
        String trimmed = response.trim();
        // Strip ```yaml ... ``` or ``` ... ``` fences
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private AIConfigDraft buildDraftFromRequest(AIConfigRequest request) {
        List<String> sourceKeys = list(first(request.getSourceKeys(), request.getKeys()));
        List<String> targetKeys = list(first(request.getTargetKeys(), request.getKeys()));
        List<String> sourceFields = list(first(request.getSourceFields(), request.getFields()));
        List<String> targetFields = list(first(request.getTargetFields(), request.getFields()));

        return AIConfigDraft.builder()
                .source(DatasetDraft.builder()
                        .type(request.getSourceType())
                        .name(request.getSourceName())
                        .jdbcUrl(request.getSourceUrl())
                        .usernameEnv(defaultEnv(request.getSourceUserEnv(), "SOURCE_USERNAME"))
                        .passwordEnv(defaultEnv(request.getSourcePasswordEnv(), "SOURCE_PASSWORD"))
                        .resourceType(request.getSourceQuery() == null ? "table" : "sql")
                        .resourceName(request.getSourceTable())
                        .query(request.getSourceQuery())
                        .build())
                .target(DatasetDraft.builder()
                        .type(request.getTargetType())
                        .name(request.getTargetName())
                        .jdbcUrl(request.getTargetUrl())
                        .usernameEnv(defaultEnv(request.getTargetUserEnv(), "TARGET_USERNAME"))
                        .passwordEnv(defaultEnv(request.getTargetPasswordEnv(), "TARGET_PASSWORD"))
                        .resourceType(request.getTargetQuery() == null ? "table" : "sql")
                        .resourceName(request.getTargetTable())
                        .query(request.getTargetQuery())
                        .build())
                .mapping(MappingDraft.builder()
                        .sourceKeys(sourceKeys)
                        .targetKeys(targetKeys)
                        .sourceFields(sourceFields)
                        .targetFields(targetFields)
                        .build())
                .strategy(StrategyDraft.builder()
                        .mode(request.getStrategyMode())
                        .algorithm(request.getAlgorithm())
                        .bisectionFactor(request.getBisectionFactor())
                        .bisectionThreshold(request.getBisectionThreshold())
                        .batchSize(request.getBatchSize())
                        .maxDifferences(request.getMaxDifferences())
                        .build())
                .result(ResultDraft.builder().sinkFormat("console").sinkType("result").build())
                .build();
    }

    private String defaultEnv(String value, String fallback) {
        return first(value, fallback);
    }

    private List<String> list(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    private String first(String explicit, String fallback) {
        return explicit == null || explicit.trim().isEmpty() ? fallback : explicit.trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
