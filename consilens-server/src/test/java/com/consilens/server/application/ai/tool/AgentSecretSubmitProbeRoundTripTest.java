package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentSecretStatus;
import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.state.SlotSource;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.connector.api.DataSourceField;
import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.application.ai.AgentRunEnqueueService;
import com.consilens.server.application.ai.AgentSecretApplicationService;
import com.consilens.server.application.ai.tool.dto.StageDatasourceDraftInput;
import com.consilens.server.application.ai.tool.dto.StageDatasourceDraftOutput;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.application.datasource.DataSourceService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.infrastructure.ai.AgentSecretCipher;
import com.consilens.server.infrastructure.ai.InMemoryAgentSecretStore;
import com.consilens.server.infrastructure.ai.MyBatisAgentPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B1/B2 regression: stage (with secret request) -> out-of-band submit ->
 * probe must carry the secretRequestId into the draft and decrypt the payload
 * back into the actual password.
 */
class AgentSecretSubmitProbeRoundTripTest {

    private MyBatisAgentPersistence persistence;
    private InMemoryAgentSecretStore store;
    private AgentSecretApplicationService submitService;
    private ConnectionTestService connectionTestService;
    private ProbeDatasourceTool probeTool;
    private ConsilensServerProperties properties;

    @BeforeEach
    void setUp() {
        SqlSessionFactory factory = com.consilens.server.infrastructure.ai.AiTestSupport
                .newSessionFactory("ai_roundtrip_" + java.util.UUID.randomUUID());
        persistence = new MyBatisAgentPersistence(factory);
        persistence.createSession(AgentSessionRecord.builder()
                .id("s1").actorId("actor").requestId("create-1")
                .status(com.consilens.agent.api.state.AgentSessionStatus.WAITING_SECRET)
                .workflowStage(AgentWorkflowStage.COLLECTING_DATASOURCES)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
        AgentSecretCipher cipher = new AgentSecretCipher(
                Base64.getEncoder().encodeToString(new byte[32]), "k1");
        store = new InMemoryAgentSecretStore(cipher);
        properties = new ConsilensServerProperties();
        properties.getAi().setSecretTtlSeconds(600);
        properties.getAi().setSecretMaxReads(8);
        submitService = new AgentSecretApplicationService(store, cipher, persistence,
                new AgentRunEnqueueService(persistence));
        connectionTestService = mock(ConnectionTestService.class);
        probeTool = new ProbeDatasourceTool(store, connectionTestService, properties);
    }

    @Test
    void stageSubmitProbeCarriesRealPassword() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.getTypeConfig("mysql")).thenReturn(List.of(
                DataSourceField.builder().field("host").title("地址").type("input").required(true).build(),
                DataSourceField.builder().field("port").title("端口").type("number").required(true).build(),
                DataSourceField.builder().field("database").title("数据库").type("input").required(true).build(),
                DataSourceField.builder().field("username").title("用户").type("input").required(true).build(),
                DataSourceField.builder().field("password").title("密码").type("input").required(true)
                        .sensitive(true).build()));
        StageDatasourceDraftTool stageTool =
                new StageDatasourceDraftTool(dataSourceService, store, properties);
        AgentWorkingState initialState = AgentWorkingState.builder()
                .objectiveId("o1").objective("obj").stage(AgentWorkflowStage.DISCOVERY).build();

        var stageOutcome = stageTool.execute(StageDatasourceDraftInput.builder()
                .side(com.consilens.server.application.ai.tool.dto.AgentDraftSide.SOURCE)
                .name("prod").type("mysql")
                .nonSecretParams(Map.of("host", "prod-db", "port", 3306,
                        "database", "shop", "username", "ro"))
                .build(), new DefaultTestToolContext("actor", "s1", initialState));
        assertTrue(stageOutcome.isSuccess());
        String secretRequestId = stageOutcome.getSecretRequestId();
        assertNotNull(secretRequestId);
        AgentWorkingState staged = stageTool.reduce(initialState, stageOutcome.getStructuredData());
        assertEquals(secretRequestId, staged.getSource().getSecretRequestId());

        boolean accepted = submitService.submit("s1", "actor", secretRequestId,
                Map.of("password", "hunter2"));
        assertTrue(accepted);

        when(connectionTestService.test(any(ConnectionTestRequest.class)))
                .thenReturn(ConnectionTestResponse.builder().success(true).latencyMs(1L).build());
        probeTool.execute(com.consilens.server.application.ai.tool.dto.ProbeDatasourceInput.builder()
                        .draftId("draft_source").build(),
                new DefaultTestToolContext("actor", "s1", staged));

        org.mockito.ArgumentCaptor<ConnectionTestRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ConnectionTestRequest.class);
        verify(connectionTestService).test(captor.capture());
        assertEquals("hunter2", captor.getValue().getPassword());
    }
}
