package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.state.AgentSecretStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.connector.api.DataSourceField;
import com.consilens.server.application.ai.tool.dto.AgentDraftSide;
import com.consilens.server.application.ai.tool.dto.StageDatasourceDraftInput;
import com.consilens.server.application.ai.tool.dto.StageDatasourceDraftOutput;
import com.consilens.server.application.datasource.DataSourceService;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.infrastructure.ai.AgentSecretCipher;
import com.consilens.server.infrastructure.ai.InMemoryAgentSecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StageDatasourceDraftToolTest {

    private final DataSourceService service = mock(DataSourceService.class);
    private final InMemoryAgentSecretStore store = new InMemoryAgentSecretStore(
            new AgentSecretCipher(Base64.getEncoder().encodeToString(new byte[32]), "k1"));
    private final ConsilensServerProperties properties = new ConsilensServerProperties();
    private final StageDatasourceDraftTool tool =
            new StageDatasourceDraftTool(service, store, properties);

    private AgentToolContext context;

    @BeforeEach
    void setUp() {
        properties.getAi().setSecretTtlSeconds(600);
        properties.getAi().setSecretMaxReads(8);
        when(service.getTypeConfig("mysql")).thenReturn(List.of(
                DataSourceField.builder().field("host").title("地址").type("input").required(true).build(),
                DataSourceField.builder().field("port").title("端口").type("number").required(true).build(),
                DataSourceField.builder().field("database").title("数据库").type("input").required(true).build(),
                DataSourceField.builder().field("username").title("用户").type("input").required(true).build(),
                DataSourceField.builder().field("password").title("密码").type("input").required(true)
                        .sensitive(true).build()));
        context = new com.consilens.server.application.ai.tool.DefaultTestToolContext("actor", "session");
    }

    @Test
    void reportsMissingNonSecretFields() {
        AgentToolOutcome<StageDatasourceDraftOutput> outcome = tool.execute(
                StageDatasourceDraftInput.builder()
                        .side(AgentDraftSide.SOURCE).name("prod").type("mysql")
                        .nonSecretParams(Map.of("host", "prod-db"))
                        .build(), context);

        assertTrue(outcome.isSuccess());
        assertFalse(outcome.getStructuredData().isReadyForSecret());
        assertTrue(outcome.getStructuredData().getMissingNonSecretFields().contains("port"));
        assertTrue(outcome.getStructuredData().getMissingNonSecretFields().contains("database"));
        assertFalse(outcome.isSecretInputRequired());
    }

    @Test
    void createsSecretRequestWhenReady() {
        AgentToolOutcome<StageDatasourceDraftOutput> outcome = tool.execute(
                StageDatasourceDraftInput.builder()
                        .side(AgentDraftSide.TARGET).name("dw").type("mysql")
                        .nonSecretParams(Map.of("host", "dw-db", "port", 9030,
                                "database", "dw", "username", "ro"))
                        .build(), context);

        assertTrue(outcome.isSuccess());
        assertTrue(outcome.getStructuredData().isReadyForSecret());
        assertEquals(List.of("password"), outcome.getStructuredData().getRequiredSecretFields());
        assertTrue(outcome.isSecretInputRequired());
        assertNotNull(outcome.getSecretRequestId());
        assertEquals("draft_target", outcome.getDraftId());
    }

    @Test
    void rejectsUnknownAndSensitiveFields() {
        AgentToolOutcome<StageDatasourceDraftOutput> unknown = tool.execute(
                StageDatasourceDraftInput.builder()
                        .side(AgentDraftSide.SOURCE).name("x").type("mysql")
                        .nonSecretParams(Map.of("evil", "1"))
                        .build(), context);
        assertEquals("TOOL_ARGUMENT_INVALID", unknown.getErrorCode());

        AgentToolOutcome<StageDatasourceDraftOutput> sensitive = tool.execute(
                StageDatasourceDraftInput.builder()
                        .side(AgentDraftSide.SOURCE).name("x").type("mysql")
                        .nonSecretParams(Map.of("password", "hunter2"))
                        .build(), context);
        assertEquals("SECRET_IN_MODEL_ARGUMENTS", sensitive.getErrorCode());
    }

    @Test
    void reduceWritesDraftAndAdvancesStage() {
        AgentWorkingState previous = AgentWorkingState.builder()
                .objectiveId("o1").objective("obj").stage(AgentWorkflowStage.DISCOVERY).build();
        AgentWorkingState next = tool.reduce(previous,
                StageDatasourceDraftOutput.builder()
                        .draftId("draft_source").readyForSecret(true)
                        .mergedNonSecretParams(Map.of("host", "h")).build());

        assertEquals(AgentWorkflowStage.COLLECTING_DATASOURCES, next.getStage());
        assertEquals("h", next.getSource().getNonSecretParams().get("host").getValue());
        assertEquals(AgentSecretStatus.REQUESTED, next.getSource().getSecretStatus());
    }
}
