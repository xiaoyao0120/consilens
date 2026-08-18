package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentProbeStatus;
import com.consilens.agent.api.state.AgentSecretStatus;
import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.state.SlotSource;
import com.consilens.agent.api.store.AgentSecretEnvelope;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.application.ai.tool.dto.ProbeDatasourceInput;
import com.consilens.server.application.ai.tool.dto.ProbeDatasourceOutput;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.infrastructure.ai.AgentSecretCipher;
import com.consilens.server.infrastructure.ai.InMemoryAgentSecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProbeDatasourceToolTest {

    private final ConnectionTestService connectionTestService = mock(ConnectionTestService.class);
    private final AgentSecretCipher cipher = new AgentSecretCipher(
            Base64.getEncoder().encodeToString(new byte[32]), "k1");
    private final InMemoryAgentSecretStore store = new InMemoryAgentSecretStore(cipher);
    private final ProbeDatasourceTool tool =
            new ProbeDatasourceTool(store, connectionTestService, new ConsilensServerProperties());

    private AgentWorkingState state;
    private String secretRequestId;

    @BeforeEach
    void setUp() {
        secretRequestId = store.createRequest("actor", "session", "draft_source",
                Instant.now().plusSeconds(600), 8);
        store.fulfill(secretRequestId, "actor", "session",
                cipher.encrypt("{\"password\":\"p@ss\"}".toCharArray(),
                        AgentSecretCipher.aad("actor", "session", secretRequestId)));
        Map<String, AgentSlotValue> slots = new LinkedHashMap<>();
        slots.put("host", AgentSlotValue.of("prod-db", SlotSource.USER_CONFIRMED));
        slots.put("port", AgentSlotValue.of(3306, SlotSource.SYSTEM_DEFAULT));
        slots.put("database", AgentSlotValue.of("shop", SlotSource.USER_CONFIRMED));
        slots.put("username", AgentSlotValue.of("ro", SlotSource.USER_CONFIRMED));
        AgentDatasourceDraftState draft = AgentDatasourceDraftState.builder()
                .draftId("draft_source").name("prod").type("mysql")
                .nonSecretParams(slots)
                .secretStatus(AgentSecretStatus.PROVIDED)
                .secretRequestId(secretRequestId)
                .build();
        state = AgentWorkingState.builder()
                .objectiveId("o1").objective("obj").source(draft).build();
    }

    @Test
    void successUpdatesProbeStatusAndConsumesRead() {
        when(connectionTestService.test(any(ConnectionTestRequest.class)))
                .thenReturn(ConnectionTestResponse.builder().success(true).latencyMs(10L).build());

        AgentToolOutcome<ProbeDatasourceOutput> outcome = tool.execute(
                ProbeDatasourceInput.builder().draftId("draft_source").build(),
                new DefaultTestToolContext("actor", "session", state));

        assertTrue(outcome.isSuccess());
        assertTrue(outcome.getStructuredData().isSuccess());
        AgentWorkingState next = tool.reduce(state, outcome.getStructuredData());
        assertEquals(AgentProbeStatus.SUCCEEDED, next.getSource().getProbeStatus());
        assertEquals(AgentWorkflowStage.VALIDATING_CONNECTIONS, next.getStage());
        verify(connectionTestService).test(any(ConnectionTestRequest.class));
    }

    @Test
    void passesTheExtractedPasswordToConnectionTest() {
        when(connectionTestService.test(any(ConnectionTestRequest.class)))
                .thenReturn(ConnectionTestResponse.builder().success(true).latencyMs(1L).build());

        tool.execute(ProbeDatasourceInput.builder().draftId("draft_source").build(),
                new DefaultTestToolContext("actor", "session", state));

        org.mockito.ArgumentCaptor<ConnectionTestRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ConnectionTestRequest.class);
        verify(connectionTestService).test(captor.capture());
        assertEquals("p@ss", captor.getValue().getPassword());
    }

    @Test
    void authFailureIsNotRetryable() {
        when(connectionTestService.test(any(ConnectionTestRequest.class)))
                .thenReturn(ConnectionTestResponse.builder().success(false)
                        .error("Access denied for user").build());

        AgentToolOutcome<ProbeDatasourceOutput> outcome = tool.execute(
                ProbeDatasourceInput.builder().draftId("draft_source").build(),
                new DefaultTestToolContext("actor", "session", state));

        assertFalse(outcome.isSuccess());
        assertEquals("DATASOURCE_PROBE_AUTH_FAILED", outcome.getErrorCode());
        assertFalse(outcome.isRetryable());
        assertTrue(outcome.isSecretInputRequired());
        assertTrue(outcome.getStructuredData().getNewSecretRequestId() != null);
    }

    @Test
    void networkFailureIsRetryable() {
        when(connectionTestService.test(any(ConnectionTestRequest.class)))
                .thenReturn(ConnectionTestResponse.builder().success(false)
                        .error("Connection timed out").build());

        AgentToolOutcome<ProbeDatasourceOutput> outcome = tool.execute(
                ProbeDatasourceInput.builder().draftId("draft_source").build(),
                new DefaultTestToolContext("actor", "session", state));

        assertFalse(outcome.isSuccess());
        assertEquals("DATASOURCE_PROBE_FAILED", outcome.getErrorCode());
        assertTrue(outcome.isRetryable());
    }

    @Test
    void expiredSecretRequestsRefill() {
        AgentDatasourceDraftState draft = AgentDatasourceDraftState.builder()
                .draftId("draft_target").name("t").type("mysql")
                .secretStatus(AgentSecretStatus.REQUESTED)
                .secretRequestId("missing")
                .build();
        AgentWorkingState noSecret = AgentWorkingState.builder()
                .objectiveId("o1").objective("obj").target(draft).build();

        AgentToolOutcome<ProbeDatasourceOutput> outcome = tool.execute(
                ProbeDatasourceInput.builder().draftId("draft_target").build(),
                new DefaultTestToolContext("actor", "session", noSecret));

        assertEquals("SECRET_REQUEST_EXPIRED", outcome.getErrorCode());
    }
}
