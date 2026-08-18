package com.consilens.agent.core.tool;

import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolPreflightTest {

    private final AgentToolPreflight preflight =
            new AgentToolPreflight(new AgentToolRegistry(List.of(new EchoTool())));

    private final AgentWorkingState state = AgentWorkingState.builder()
            .objectiveId("o1")
            .objective("test")
            .stage(AgentWorkflowStage.DISCOVERY)
            .build();

    private PreflightResult prepare(String name, String args) {
        return preflight.prepare(AgentModelToolCall.builder()
                .id("c1").name(name).arguments(args).index(0).build(),
                state, "s1", Set.of(), 0);
    }

    @Test
    void unknownToolIsBlocked() {
        PreflightResult result = prepare("rm_rf", "{}");
        assertEquals(PreflightStatus.BLOCKED, result.getStatus());
        assertEquals("UNKNOWN_TOOL", result.getErrorCode());
    }

    @Test
    void malformedJsonIsBlocked() {
        PreflightResult result = prepare("echo", "{not json");
        assertEquals(PreflightStatus.BLOCKED, result.getStatus());
        assertEquals("TOOL_ARGUMENT_INVALID", result.getErrorCode());
    }

    @Test
    void missingRequiredFieldIsBlocked() {
        PreflightResult result = prepare("echo", "{\"unexpected\":1}");
        assertEquals(PreflightStatus.BLOCKED, result.getStatus());
        assertEquals("TOOL_ARGUMENT_INVALID", result.getErrorCode());
    }

    @Test
    void secretShapedArgumentIsBlockedBeforeExecution() {
        AgentToolPreflight loosePreflight = new AgentToolPreflight(
                new AgentToolRegistry(List.of(new LooseSchemaTool())));
        PreflightResult result = loosePreflight.prepare(AgentModelToolCall.builder()
                .id("c1").name("loose").arguments("{\"text\":\"hi\",\"password\":\"x\"}").index(0).build(),
                state, "s1", Set.of(), 0);
        assertEquals(PreflightStatus.BLOCKED, result.getStatus());
        assertEquals("SECRET_IN_MODEL_ARGUMENTS", result.getErrorCode());
        assertNull(result.getPrepared());
    }

    @Test
    void allowedCallCarriesCanonicalDigestsAndParsedInput() {
        PreflightResult result = prepare("echo", "{\"text\":\"hello\"}");
        assertEquals(PreflightStatus.ALLOWED, result.getStatus());
        PreparedToolCall prepared = result.getPrepared();
        assertNotNull(prepared);
        assertTrue(prepared.getArgsDigest().matches("^[0-9a-f]{64}$"));
        assertTrue(prepared.getActionDigest().matches("^[0-9a-f]{64}$"));
        EchoInput input = (EchoInput) prepared.getInput();
        assertEquals("hello", input.getText());
    }

    @Test
    void stageGatedToolsAreBlockedOutsideAllowedStages() {
        AgentToolPreflight gated = new AgentToolPreflight(new AgentToolRegistry(List.of(
                new StageGatedTool())));
        PreflightResult result = gated.prepare(AgentModelToolCall.builder()
                .id("c1").name("gated").arguments("{\"text\":\"x\"}").index(0).build(),
                state, "s1", Set.of(), 0);
        assertEquals(PreflightStatus.BLOCKED, result.getStatus());
        assertEquals("TOOL_NOT_ALLOWED_IN_STAGE", result.getErrorCode());
    }

    private static final class StageGatedTool extends EchoTool {
        @Override
        public com.consilens.agent.api.tool.AgentToolDescriptor descriptor() {
            return super.descriptor().withName("gated").withAllowedStages(
                    java.util.Set.of(AgentWorkflowStage.PLAN_READY));
        }
    }

    /** Echo variant whose schema allows unknown properties (loose schemas exist in real forms). */
    private static final class LooseSchemaTool extends EchoTool {
        @Override
        public com.consilens.agent.api.tool.AgentToolDescriptor descriptor() {
            return super.descriptor().withName("loose");
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode inputSchema() {
            com.fasterxml.jackson.databind.node.ObjectNode properties =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            properties.set("text", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .objectNode().put("type", "string"));
            com.fasterxml.jackson.databind.node.ObjectNode schema =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            schema.set("required", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                    .arrayNode().add("text"));
            schema.put("additionalProperties", true);
            return schema;
        }
    }
}
