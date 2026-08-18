package com.consilens.agent.core.loop;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.model.AgentMessageRole;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.agent.core.tool.AgentToolRegistry;
import com.consilens.agent.core.tool.EchoTool;
import com.consilens.agent.core.tool.QuestionTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextAssemblerTest {

    private final AgentContextAssembler assembler = new AgentContextAssembler("base prompt", 50);
    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(new EchoTool(), new QuestionTool()));

    @Test
    void exposesOnlyModelVisibleEventsInOrder() {
        AgentWorkingState state = AgentWorkingState.builder()
                .objectiveId("o1")
                .objective("obj")
                .stage(AgentWorkflowStage.DISCOVERY)
                .build();
        List<AgentEvent> events = List.of(
                AgentEventFactory.create(AgentEventType.USER_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                        "s1", "r1", null, AgentEventFactory.payload().put("text", "hello")),
                AgentEventFactory.create(AgentEventType.MODEL_USAGE, AgentEventVisibility.AUDIT_ONLY,
                        "s1", "r1", "t1", null),
                AgentEventFactory.create(AgentEventType.ASSISTANT_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                        "s1", "r1", "t1", AgentEventFactory.payload().put("text", "hi back")));

        AgentModelRequest request = assembler.build(state, events, registry);

        assertEquals(AgentMessageRole.SYSTEM, request.getMessages().get(0).getRole());
        assertEquals(AgentMessageRole.SYSTEM, request.getMessages().get(1).getRole());
        assertTrue(request.getMessages().get(1).getContent().contains("\"objectiveId\""));
        assertEquals(AgentMessageRole.USER, request.getMessages().get(2).getRole());
        assertEquals("hello", request.getMessages().get(2).getContent());
        assertEquals(AgentMessageRole.ASSISTANT, request.getMessages().get(3).getRole());
        assertEquals("hi back", request.getMessages().get(3).getContent());
        // MODEL_USAGE is audit-only and must never reach the model.
        assertEquals(4, request.getMessages().size());
    }

    @Test
    void stageFilteringHidesToolsNotAllowedInCurrentStage() {
        AgentWorkingState state = AgentWorkingState.builder()
                .objectiveId("o1")
                .objective("obj")
                .stage(AgentWorkflowStage.VALIDATING_CONNECTIONS)
                .build();

        AgentModelRequest request = assembler.build(state, List.of(), registry);

        assertTrue(request.getToolDefinitions().stream().anyMatch(t -> t.getName().equals("echo")));
        assertTrue(request.getToolDefinitions().stream().anyMatch(t -> t.getName().equals("request_user_input")));
    }
}
