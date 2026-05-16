package com.consilens.cli.ai.runtime;

import com.consilens.ai.execution.ConfigCapability;
import com.consilens.ai.execution.model.ConfigGenerationRequest;
import com.consilens.ai.execution.model.GeneratedConfig;
import com.consilens.ai.runtime.model.AiTaskContext;
import com.consilens.ai.runtime.model.AiTaskEvent;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        List<AiTaskEvent> events = new ArrayList<>();
        ArtifactRef diagnosisArtifact = artifactStore.latest(context.getSession().getSessionId(), ArtifactType.DIAGNOSIS).orElse(null);
        if (diagnosisArtifact == null) {
            return failure(type(), "No diagnosis artifact found for repair.",
                    List.of(event("load-failure-context", "failed", "No diagnosis artifact found for repair.")));
        }
        ArtifactRef currentConfigArtifact = context.getSession().getCurrentConfigArtifactId() == null
                ? null
                : artifactStore.get(context.getSession().getCurrentConfigArtifactId()).orElse(null);
        if (currentConfigArtifact == null) {
            return failure(type(), "No current config artifact found for repair.",
                    List.of(event("load-failure-context", "failed", "No current config artifact found for repair.")));
        }
        events.add(event("load-failure-context", "completed",
                "Loaded diagnosis " + diagnosisArtifact.getArtifactId() + " and config " + currentConfigArtifact.getArtifactId()));
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
                Map.of("task", "repair",
                        "sourceConfigArtifactId", currentConfigArtifact.getArtifactId(),
                        "diagnosisArtifactId", diagnosisArtifact.getArtifactId()));
        events.add(event("generate-patch", "completed",
                "Generated repaired config " + repairedConfigArtifact.getArtifactId(), repairedConfigArtifact));
        List<String> changedSections = changedSections(currentConfig, repaired.getConfigRef().getContent());

        StringBuilder patch = new StringBuilder()
                .append("# AI Repair Plan").append(System.lineSeparator()).append(System.lineSeparator())
                .append("Status: READY_FOR_RETRY").append(System.lineSeparator())
                .append("Session: ").append(context.getSession().getSessionId()).append(System.lineSeparator())
                .append("Diagnosis: ").append(diagnosisArtifact.getArtifactId()).append(System.lineSeparator());
        patch.append("Current Config: ").append(currentConfigArtifact.getArtifactId()).append(System.lineSeparator());
        patch.append("Repaired Config: ").append(repairedConfigArtifact.getArtifactId()).append(System.lineSeparator());
        patch.append("Changed Sections: ").append(changedSections.isEmpty() ? "(unknown)" : String.join(", ", changedSections))
                .append(System.lineSeparator());
        patch.append("Next Action: RUN_AGAIN").append(System.lineSeparator());
        if (!changedSections.isEmpty()) {
            patch.append(System.lineSeparator())
                    .append("Changes:").append(System.lineSeparator());
            for (String section : changedSections) {
                patch.append(renderSectionChange(section, currentConfig, repaired.getConfigRef().getContent()));
            }
        }
        patch.append(System.lineSeparator())
                .append("Recommended repair loop:").append(System.lineSeparator())
                .append("1. Review the regenerated config artifact and diagnosis below.").append(System.lineSeparator())
                .append("2. Run `/validate` or `/dry-run` if you want an explicit preflight on the repaired config.").append(System.lineSeparator())
                .append("3. If needed, refine keys, fields, normalization, or connector hints.").append(System.lineSeparator())
                .append("4. Re-run `consilens ai run --session ").append(context.getSession().getSessionId())
                .append(" --approve-execute` to verify the fix.").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(diagnosis);
        ArtifactRef patchArtifact = writeArtifact(
                context.getSession().getSessionId(),
                ArtifactType.REPAIR_PATCH,
                patch.toString(),
                Map.of("task", "repair",
                        "diagnosisArtifactId", diagnosisArtifact.getArtifactId(),
                        "sourceConfigArtifactId", currentConfigArtifact.getArtifactId(),
                        "repairedConfigArtifactId", repairedConfigArtifact.getArtifactId()));
        events.add(event("ready-for-retry", "completed",
                "Prepared repair patch " + patchArtifact.getArtifactId(), patchArtifact));
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
                .summary("Repair status: READY_FOR_RETRY"
                        + System.lineSeparator() + "patch=" + patchArtifact.getArtifactId()
                        + System.lineSeparator() + "repairedConfig=" + repairedConfigArtifact.getArtifactId()
                        + System.lineSeparator() + "changedSections="
                        + (changedSections.isEmpty() ? "(unknown)" : String.join(", ", changedSections))
                        + System.lineSeparator() + patch)
                .suggestedNextAction("run")
                .events(events)
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

    private List<String> changedSections(String currentConfig, String repairedConfig) {
        try {
            JsonNode current = yamlMapper.readTree(currentConfig);
            JsonNode repaired = yamlMapper.readTree(repairedConfig);
            Set<String> keys = new LinkedHashSet<>();
            collectKeys(keys, current);
            collectKeys(keys, repaired);
            List<String> changed = new ArrayList<>();
            for (String key : keys) {
                JsonNode left = current == null ? null : current.get(key);
                JsonNode right = repaired == null ? null : repaired.get(key);
                if (left == null && right == null) {
                    continue;
                }
                if (left == null || right == null || !left.equals(right)) {
                    changed.add(key);
                }
            }
            return changed;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void collectKeys(Set<String> keys, JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            keys.add(fieldNames.next());
        }
    }

    private String renderSectionChange(String section, String currentConfig, String repairedConfig) {
        try {
            JsonNode current = yamlMapper.readTree(currentConfig);
            JsonNode repaired = yamlMapper.readTree(repairedConfig);
            JsonNode before = current == null ? null : current.get(section);
            JsonNode after = repaired == null ? null : repaired.get(section);
            StringBuilder builder = new StringBuilder();
            builder.append("- ").append(section).append(System.lineSeparator());
            builder.append("  Before:").append(System.lineSeparator());
            builder.append(indentSection(before)).append(System.lineSeparator());
            builder.append("  After:").append(System.lineSeparator());
            builder.append(indentSection(after)).append(System.lineSeparator());
            return builder.toString();
        } catch (Exception e) {
            return "- " + section + System.lineSeparator()
                    + "  Before:" + System.lineSeparator()
                    + "    (unavailable)" + System.lineSeparator()
                    + "  After:" + System.lineSeparator()
                    + "    (unavailable)" + System.lineSeparator();
        }
    }

    private String indentSection(JsonNode node) {
        try {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "    (none)";
            }
            String yaml = yamlMapper.writeValueAsString(node).trim();
            StringBuilder builder = new StringBuilder();
            for (String line : yaml.split("\\R")) {
                builder.append("    ").append(line).append(System.lineSeparator());
            }
            if (builder.length() > 0) {
                builder.setLength(builder.length() - System.lineSeparator().length());
            }
            return builder.toString();
        } catch (Exception e) {
            return "    (unavailable)";
        }
    }
}
