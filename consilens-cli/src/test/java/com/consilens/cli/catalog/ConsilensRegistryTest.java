package com.consilens.cli.catalog;

import com.consilens.cli.mcp.ConsilensMcpToolRegistry;
import com.consilens.cli.skills.ConsilensSkillRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsilensRegistryTest {

    @Test
    void shouldSupportDocAlignedMcpTools() {
        assertTrue(ConsilensMcpToolRegistry.supports("consilens.plan.config"));
        assertTrue(ConsilensMcpToolRegistry.supports("consilens.validate.config"));
        assertTrue(ConsilensMcpToolRegistry.supports("consilens.run.diff"));
        assertTrue(ConsilensMcpToolRegistry.supports("consilens.diagnose.diff"));
        assertTrue(ConsilensMcpToolRegistry.supports("consilens.repair.config"));
        assertTrue(ConsilensMcpToolRegistry.supports("consilens.save.config"));
        assertTrue(ConsilensMcpToolRegistry.supports("consilens.get.artifact"));
        assertFalse(ConsilensMcpToolRegistry.supports("consilens.memory.remember"));
        assertFalse(ConsilensMcpToolRegistry.supports("consilens.explain.config"));
    }

    @Test
    void shouldExposeServerApiBackedToolDefinitions() {
        ConsilensMcpToolRegistry.ToolDefinition run = ConsilensMcpToolRegistry.find("consilens.run.diff")
                .orElseThrow();

        assertEquals("POST", run.getHttpMethod());
        assertEquals("/v1/run", run.getApiPath());
        assertTrue(((java.util.Map<?, ?>) run.getInputSchema().get("properties")).containsKey("configArtifactId"));
    }

    @Test
    void shouldPublishDocAlignedSkills() {
        assertTrue(ConsilensSkillRegistry.skillIds().contains("compare-config-build"));
        assertTrue(ConsilensSkillRegistry.skillIds().contains("compare-closed-loop-run"));
        assertTrue(ConsilensSkillRegistry.skillIds().contains("compare-diagnose-repair"));
        assertTrue(ConsilensSkillRegistry.skillIds().contains("aggregate-compare-build"));
        assertFalse(ConsilensSkillRegistry.skillIds().contains("compare-run-closed-loop"));
        assertTrue(ConsilensSkillRegistry.find("compare-closed-loop-run")
                .map(skill -> skill.getSteps().stream().allMatch(step -> step.getOnFailure() != null))
                .orElse(false));
    }
}
