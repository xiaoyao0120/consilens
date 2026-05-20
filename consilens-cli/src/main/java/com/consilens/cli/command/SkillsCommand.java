package com.consilens.cli.command;

import com.consilens.cli.skills.ConsilensSkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "skills",
        description = "List stateless Consilens skill recipes",
        mixinStandardHelpOptions = true
)
public class SkillsCommand implements Callable<Integer> {

    private final ObjectMapper objectMapper;

    @Parameters(index = "0", arity = "0..1", description = "Optional skill id to print")
    private String skillId;

    @Option(names = "--format", defaultValue = "text", description = "Output format: text or json")
    private String format;

    @Spec
    private CommandSpec spec;

    public SkillsCommand() {
        this(new ObjectMapper());
    }

    SkillsCommand(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Integer call() throws Exception {
        if ("json".equalsIgnoreCase(format)) {
            spec.commandLine().getOut().println(objectMapper.writeValueAsString(payload()));
            return 0;
        }
        if (!"text".equalsIgnoreCase(format)) {
            spec.commandLine().getErr().println("Unsupported format: " + format + ". Use text or json.");
            return 2;
        }
        PrintWriter out = spec.commandLine().getOut();
        if (skillId == null || skillId.isBlank()) {
            out.println("# Consilens Skills");
            ConsilensSkillRegistry.skillIds().forEach(id -> out.println("- " + id));
        } else {
            ConsilensSkillRegistry.SkillDefinition skill = ConsilensSkillRegistry.find(skillId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + skillId));
            out.println("# " + skill.getId());
            out.println(skill.getDescription());
            skill.getSteps().forEach(step -> out.println("- " + step.getTool() + " onFailure=" + step.getOnFailure()));
        }
        out.flush();
        return 0;
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (skillId == null || skillId.isBlank()) {
            payload.put("skills", ConsilensSkillRegistry.definitions());
        } else {
            payload.put("skill", ConsilensSkillRegistry.find(skillId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown skill: " + skillId)));
        }
        return payload;
    }
}
