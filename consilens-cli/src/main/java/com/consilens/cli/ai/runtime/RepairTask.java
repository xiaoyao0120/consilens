package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskResult;
import com.consilens.ai.runtime.model.AiTurnResult;
import com.consilens.ai.runtime.task.AiTask;
import com.consilens.ai.runtime.task.AiTaskType;
import com.consilens.ai.session.AiArtifactStore;
import com.consilens.ai.session.AiMemoryStore;
import com.consilens.ai.session.AiSessionStore;
import com.consilens.ai.session.model.ArtifactRef;
import com.consilens.ai.session.model.ArtifactType;
import com.consilens.cli.model.CliConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Produces a repair plan artifact and a next-round repaired config artifact.
 */
public class RepairTask extends AbstractAiTask implements AiTask {

    private final ConfigCapability configCapability;
    private final ObjectMapper yamlMapper;

    public RepairTask(ConfigCapability configCapability, AiSessionStore sessionStore, AiArtifactStore artifactStore) {
        this(configCapability, sessionStore, artifactStore, null);
    }

    public RepairTask(ConfigCapability configCapability,
                      AiSessionStore sessionStore,
                      AiArtifactStore artifactStore,
                      AiMemoryStore memoryStore) {
        super(sessionStore, artifactStore, memoryStore);
        this.configCapability = configCapability;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.REPAIR;
    }

    @Override
    public AiTaskResult execute(AiTaskContext context) {
        ArtifactRef diagnosisArtifact = artifactStore.latest(context.getSession().getSessionId(), ArtifactType.DIAGNOSIS).orElse(null);
        if (diagnosisArtifact == null) {
            return failure(type(), "No diagnosis artifact found for repair.");
        }
        ArtifactRef currentConfigArtifact = context.getSession().getCurrentConfigArtifactId() == null
                ? null
                : artifactStore.get(context.getSession().getCurrentConfigArtifactId()).orElse(null);
        if (currentConfigArtifact == null) {
            return failure(type(), "No current config artifact found for repair.");
        }
        String diagnosis = artifactStore.read(diagnosisArtifact.getArtifactId())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse("Diagnosis artifact content unavailable.");
        String currentConfig = artifactStore.read(currentConfigArtifact.getArtifactId())
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalStateException("Current config artifact content unavailable."));

        ConfigGenerationRequest repairPrompt = buildRepairRequest(context, currentConfigArtifact, currentConfig, diagnosis);
        ConfigGenerationRequest repairRequest = enrichWithMemories(repairPrompt, context.getSession().getSessionId());
        GeneratedConfig repaired = configCapability.generate(repairRequest);
        ArtifactRef repairedConfigArtifact = writeArtifact(
                context.getSession().getSessionId(),
                ArtifactType.CONFIG,
                repaired.getConfigRef().getContent(),
                Map.of("task", "repair", "sourceConfigArtifactId", currentConfigArtifact.getArtifactId()));

        StringBuilder patch = new StringBuilder()
                .append("# AI Repair Plan").append(System.lineSeparator()).append(System.lineSeparator())
                .append("Session: ").append(context.getSession().getSessionId()).append(System.lineSeparator())
                .append("Diagnosis: ").append(diagnosisArtifact.getArtifactId()).append(System.lineSeparator());
        patch.append("Current Config: ").append(currentConfigArtifact.getArtifactId()).append(System.lineSeparator());
        patch.append("Repaired Config: ").append(repairedConfigArtifact.getArtifactId()).append(System.lineSeparator());
        patch.append(System.lineSeparator())
                .append("Recommended repair loop:").append(System.lineSeparator())
                .append("1. Review the regenerated config artifact and diagnosis below.").append(System.lineSeparator())
                .append("2. If needed, refine keys, fields, normalization, or connector hints.").append(System.lineSeparator())
                .append("3. Re-run `consilens ai run --session ").append(context.getSession().getSessionId())
                .append(" --approve-execute` to verify the fix.").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(diagnosis);
        ArtifactRef patchArtifact = writeArtifact(
                context.getSession().getSessionId(),
                ArtifactType.REPAIR_PATCH,
                patch.toString(),
                Map.of("task", "repair", "diagnosisArtifactId", diagnosisArtifact.getArtifactId()));
        writeOutput(outputPath(context), repaired.getConfigRef().getContent());
        updateSession(context.getSession(), builder -> builder
                .currentTask("repair")
                .status("repair_ready")
                .currentConfigArtifactId(repairedConfigArtifact.getArtifactId()));
        remember("repair", repairPrompt.getGoal(), "repair:" + context.getSession().getSessionId());
        return AiTaskResult.builder()
                .success(true)
                .taskType(type())
                .status(AiTurnResult.Status.COMPLETED)
                .summary("Created repair plan " + patchArtifact.getArtifactId()
                        + " and regenerated config " + repairedConfigArtifact.getArtifactId()
                        + System.lineSeparator() + patch)
                .suggestedNextAction("run")
                .build();
    }

    private ConfigGenerationRequest buildRepairRequest(AiTaskContext context,
                                                       ArtifactRef currentConfigArtifact,
                                                       String currentConfig,
                                                       String diagnosis) {
        try {
            CliConfiguration config = yamlMapper.readValue(currentConfig, CliConfiguration.class);
            List<String> hints = new ArrayList<>();
            addHint(hints, "sourceType", config.getSource().getType());
            addHint(hints, "sourceUrl", config.getSource().getConnection().getUrl());
            addHint(hints, "sourceName", config.getSource().getName());
            addHint(hints, "sourceTable", config.getSource().getResource().getName());
            addHint(hints, "sourceQuery", config.getSource().getResource().getPath());
            addHint(hints, "sourceUserEnv", envName(config.getSource().getConnection().getUsername()));
            addHint(hints, "sourcePasswordEnv", envName(config.getSource().getConnection().getPassword()));
            addHint(hints, "targetType", config.getTarget().getType());
            addHint(hints, "targetUrl", config.getTarget().getConnection().getUrl());
            addHint(hints, "targetName", config.getTarget().getName());
            addHint(hints, "targetTable", config.getTarget().getResource().getName());
            addHint(hints, "targetQuery", config.getTarget().getResource().getPath());
            addHint(hints, "targetUserEnv", envName(config.getTarget().getConnection().getUsername()));
            addHint(hints, "targetPasswordEnv", envName(config.getTarget().getConnection().getPassword()));
            if (config.getComparison() != null && config.getComparison().getKeys() != null) {
                addHint(hints, "sourceKeys", String.join(",", config.getComparison().getKeys().getSource()));
                addHint(hints, "targetKeys", String.join(",", config.getComparison().getKeys().getTarget()));
            }
            if (config.getComparison() != null && config.getComparison().getFields() != null) {
                addHint(hints, "sourceFields", String.join(",", config.getComparison().getFields().getSource()));
                addHint(hints, "targetFields", String.join(",", config.getComparison().getFields().getTarget()));
            }
            addHint(hints, "strategyMode", config.getStrategyMode());
            addHint(hints, "algorithm", config.getAlgorithm());
            addHint(hints, "batchSize", config.getStrategy().getBatchSize());
            addHint(hints, "maxDifferences", config.getStrategy().getMaxDifferences());

            String goal = (currentConfigArtifact.getMetadata() == null
                    ? null
                    : currentConfigArtifact.getMetadata().get("goal"));
            if (goal == null || goal.isBlank()) {
                goal = context.getSession().getTitle();
            }
            if (goal == null || goal.isBlank()) {
                goal = "Repair current Consilens diff config";
            }
            goal = goal + System.lineSeparator()
                    + "Repair based on latest diagnosis:" + System.lineSeparator()
                    + diagnosis;
            ConfigGenerationRequest.ConfigGenerationRequestBuilder builder = ConfigGenerationRequest.builder()
                    .sessionId(context.getSession().getSessionId())
                    .goal(goal);
            hints.forEach(builder::hint);
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build repair request from current config", e);
        }
    }

    private void addHint(List<String> hints, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String && ((String) value).trim().isEmpty()) {
            return;
        }
        hints.add(key + "=" + value);
    }

    private String envName(String placeholder) {
        if (placeholder == null || placeholder.isBlank()) {
            return null;
        }
        String trimmed = placeholder.trim();
        if (trimmed.startsWith("${env.") && trimmed.endsWith("}")) {
            return trimmed.substring("${env.".length(), trimmed.length() - 1);
        }
        return trimmed;
    }
}
