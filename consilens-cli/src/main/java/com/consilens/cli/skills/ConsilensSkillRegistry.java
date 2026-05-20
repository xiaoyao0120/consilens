package com.consilens.cli.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Skill catalog for external agents.
 * Skills are defined as ordered MCP tool chains with explicit intent.
 */
public final class ConsilensSkillRegistry {

    public static final class SkillStep {
        private final String tool;
        private final String onFailure;

        public SkillStep(String tool, String onFailure) {
            this.tool = tool;
            this.onFailure = onFailure;
        }

        public String getTool() {
            return tool;
        }

        public String getOnFailure() {
            return onFailure;
        }
    }

    public static final class SkillDefinition {
        private final String id;
        private final String description;
        private final List<SkillStep> steps;

        public SkillDefinition(String id, String description, List<SkillStep> steps) {
            this.id = id;
            this.description = description;
            this.steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public String getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        public List<SkillStep> getSteps() {
            return steps;
        }

        public List<String> getTools() {
            if (steps == null || steps.isEmpty()) {
                return List.of();
            }
            return steps.stream().map(SkillStep::getTool).collect(java.util.stream.Collectors.toList());
        }
    }

    private static final Map<String, SkillDefinition> SKILLS;

    static {
        Map<String, SkillDefinition> defs = new LinkedHashMap<>();
        defs.put("compare-config-build", new SkillDefinition(
                "compare-config-build",
                "Build compare config and validate it.",
                List.of(
                        step("consilens.plan.config", "abort"),
                        step("consilens.validate.config", "abort"))));
        defs.put("compare-closed-loop-run", new SkillDefinition(
                "compare-closed-loop-run",
                "Validate config, execute diff, then diagnose diff result.",
                List.of(
                        step("consilens.validate.config", "abort"),
                        step("consilens.run.diff", "abort"),
                        step("consilens.diagnose.diff", "continue-with-error"))));
        defs.put("compare-diagnose-repair", new SkillDefinition(
                "compare-diagnose-repair",
                "Diagnose diff result then generate executable repair config.",
                List.of(
                        step("consilens.diagnose.diff", "abort"),
                        step("consilens.repair.config", "abort"))));
        defs.put("aggregate-compare-build", new SkillDefinition(
                "aggregate-compare-build",
                "Build and validate multiple compare configs in batch pipelines.",
                List.of(
                        step("consilens.plan.config", "abort"),
                        step("consilens.validate.config", "abort"))));
        SKILLS = Map.copyOf(defs);
    }

    private ConsilensSkillRegistry() {
    }

    public static Set<String> skillIds() {
        return SKILLS.keySet();
    }

    public static List<SkillDefinition> definitions() {
        return List.copyOf(SKILLS.values());
    }

    public static Optional<SkillDefinition> find(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SKILLS.get(skillId));
    }

    private static SkillStep step(String tool, String onFailure) {
        return new SkillStep(tool, onFailure);
    }
}
