package com.consilens.cli.ai;

import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.ConfigRef;
import com.consilens.ai.execution.model.DryRunReport;
import com.consilens.ai.execution.model.ExplainReport;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.execution.model.ValidationReport;
import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.service.DiffService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CLI-backed deterministic capability implementation for config operations.
 */
public class DefaultConfigCapability implements ConfigCapability {

    private final AIConfigService aiConfigService;
    private final ConfigurationManager configurationManager;
    private final DiffService diffService;
    private final AIExplainService explainService;
    private final ObjectMapper rawYamlMapper;

    public DefaultConfigCapability() {
        this(new AIConfigService(), new ConfigurationManager(), new DiffService(), new AIExplainService());
    }

    DefaultConfigCapability(AIConfigService aiConfigService,
                            ConfigurationManager configurationManager,
                            DiffService diffService,
                            AIExplainService explainService) {
        this.aiConfigService = aiConfigService;
        this.configurationManager = configurationManager;
        this.diffService = diffService;
        this.explainService = explainService;
        this.rawYamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @Override
    public GeneratedConfig generate(ConfigGenerationRequest request) {
        AIConfigResult result = aiConfigService.generate(toRequest(request));
        if (!result.isValid()) {
            throw new IllegalArgumentException("AI config generation failed: " + summarizeIssues(result.getIssues()));
        }
        Map<String, String> hints = hintMap(request);
        GeneratedConfig.GeneratedConfigBuilder builder = GeneratedConfig.builder()
                .configRef(ConfigRef.builder()
                        .sessionId(request.getSessionId())
                        .content(result.getYaml())
                        .build());
        envHint(hints, "sourceUserEnv").ifPresent(builder::requiredEnv);
        envHint(hints, "sourcePasswordEnv").ifPresent(builder::requiredEnv);
        envHint(hints, "targetUserEnv").ifPresent(builder::requiredEnv);
        envHint(hints, "targetPasswordEnv").ifPresent(builder::requiredEnv);
        if (result.getDraft() != null && result.getDraft().getAssumptions() != null) {
            result.getDraft().getAssumptions().forEach(builder::assumption);
        }
        return builder.build();
    }

    @Override
    public ValidationReport validate(ConfigRef configRef) {
        try {
            loadResolved(configRef);
            return ValidationReport.builder().passed(true).message("Configuration validation passed").build();
        } catch (Exception e) {
            return ValidationReport.builder().passed(false).message(e.getMessage()).build();
        }
    }

    @Override
    public DryRunReport dryRun(ConfigRef configRef) {
        try {
            CliDiffResult result = diffService.performDryRun(loadResolved(configRef));
            return DryRunReport.builder()
                    .passed(true)
                    .message("Source rows=" + result.getSourceRowCount())
                    .message("Target rows=" + result.getTargetRowCount())
                    .build();
        } catch (Exception e) {
            return DryRunReport.builder().passed(false).message(e.getMessage()).build();
        }
    }

    @Override
    public ExplainReport explain(ConfigRef configRef) {
        try {
            CliConfiguration config = rawYamlMapper.readValue(configRef.getContent(), CliConfiguration.class);
            config.validate();
            return ExplainReport.builder()
                    .markdown(explainService.explain(config))
                    .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("AI explain failed: " + e.getMessage(), e);
        }
    }

    private CliConfiguration loadResolved(ConfigRef configRef) throws Exception {
        return configurationManager.loadConfiguration(
                new ByteArrayInputStream(configRef.getContent().getBytes(StandardCharsets.UTF_8)), "yaml");
    }

    private AIConfigRequest toRequest(ConfigGenerationRequest request) {
        Map<String, String> hints = hintMap(request);
        return AIConfigRequest.builder()
                .goal(request.getGoal())
                .sourceType(hints.get("sourceType"))
                .sourceUrl(hints.get("sourceUrl"))
                .sourceName(hints.get("sourceName"))
                .sourceTable(hints.get("sourceTable"))
                .sourceQuery(hints.get("sourceQuery"))
                .sourceUserEnv(hints.get("sourceUserEnv"))
                .sourcePasswordEnv(hints.get("sourcePasswordEnv"))
                .targetType(hints.get("targetType"))
                .targetUrl(hints.get("targetUrl"))
                .targetName(hints.get("targetName"))
                .targetTable(hints.get("targetTable"))
                .targetQuery(hints.get("targetQuery"))
                .targetUserEnv(hints.get("targetUserEnv"))
                .targetPasswordEnv(hints.get("targetPasswordEnv"))
                .keys(hints.get("keys"))
                .sourceKeys(hints.get("sourceKeys"))
                .targetKeys(hints.get("targetKeys"))
                .fields(hints.get("fields"))
                .sourceFields(hints.get("sourceFields"))
                .targetFields(hints.get("targetFields"))
                .strategyMode(hints.get("strategyMode"))
                .algorithm(hints.get("algorithm"))
                .bisectionFactor(integer(hints.get("bisectionFactor")))
                .bisectionThreshold(longValue(hints.get("bisectionThreshold")))
                .batchSize(integer(hints.get("batchSize")))
                .maxDifferences(longValue(hints.get("maxDifferences")))
                .backendOptions(AIBackendOptions.builder()
                        .backend(hints.get("backend"))
                        .model(hints.get("model"))
                        .baseUrl(hints.get("baseUrl"))
                        .apiKey(hints.get("apiKey"))
                        .timeout(hints.get("timeout"))
                        .temperature(doubleValue(hints.get("temperature")))
                        .maxTokens(integer(hints.get("maxTokens")))
                        .noLlm(Boolean.parseBoolean(hints.getOrDefault("noLlm", "false")))
                        .build())
                .build();
    }

    private Map<String, String> hintMap(ConfigGenerationRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        if (request.getHints() == null) {
            return values;
        }
        for (String hint : request.getHints()) {
            if (hint == null) {
                continue;
            }
            int index = hint.indexOf('=');
            if (index <= 0) {
                continue;
            }
            values.put(hint.substring(0, index), hint.substring(index + 1));
        }
        return values;
    }

    private java.util.Optional<String> envHint(Map<String, String> hints, String key) {
        String value = hints.get(key);
        return value == null || value.trim().isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(value.trim());
    }

    private Integer integer(String value) {
        return value == null || value.trim().isEmpty() ? null : Integer.valueOf(value.trim());
    }

    private Long longValue(String value) {
        return value == null || value.trim().isEmpty() ? null : Long.valueOf(value.trim());
    }

    private Double doubleValue(String value) {
        return value == null || value.trim().isEmpty() ? null : Double.valueOf(value.trim());
    }

    private String summarizeIssues(List<AIConfigIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "unknown validation error";
        }
        return issues.stream()
                .map(issue -> issue.getCode() + "@" + issue.getPath() + ": " + issue.getMessage())
                .collect(Collectors.joining("; "));
    }
}
