package com.consilens.agent.eval;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.model.AgentMessageRole;
import com.consilens.agent.api.model.AgentModelFinishReason;
import com.consilens.agent.api.model.AgentModelMessage;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedAgentModelClientTest {

    @Test
    void playsBackScriptInOrderAndRecordsRequests() {
        ScriptedAgentModelClient client = new ScriptedAgentModelClient(
                AgentModelResponse.success("first", List.of(), AgentModelFinishReason.STOP,
                        null, true, 1),
                AgentModelResponse.success("second", List.of(), AgentModelFinishReason.STOP,
                        null, true, 1));
        AgentModelRequest request = AgentModelRequest.builder()
                .messages(List.of(AgentModelMessage.builder()
                        .role(AgentMessageRole.USER).content("hi").build()))
                .build();

        assertEquals("first", client.complete(request, new NoopListener(),
                AgentCancellationToken.NEVER_CANCELLED).getText());
        assertEquals("second", client.complete(request, new NoopListener(),
                AgentCancellationToken.NEVER_CANCELLED).getText());
        assertEquals(2, client.receivedRequests().size());
        assertTrue(client.isExhausted());
    }

    @Test
    void exhaustedScriptFailsLoudly() {
        ScriptedAgentModelClient client = new ScriptedAgentModelClient();
        assertThrows(IllegalStateException.class,
                () -> client.complete(AgentModelRequest.builder().build(), new NoopListener(),
                        AgentCancellationToken.NEVER_CANCELLED));
    }

    @Test
    void cancellationReturnsModelCancelledWithoutConsumingScript() {
        ScriptedAgentModelClient client = new ScriptedAgentModelClient(
                AgentModelResponse.success("never", List.of(), AgentModelFinishReason.STOP,
                        null, true, 1));

        AgentModelResponse response = client.complete(AgentModelRequest.builder().build(),
                new NoopListener(), () -> true);

        assertTrue(response.isFailed());
        assertEquals("MODEL_CANCELLED", response.getErrorCode());
        assertFalse(client.isExhausted());
    }

    private static final class NoopListener implements com.consilens.agent.api.model.AgentModelEventListener {
    }
}
