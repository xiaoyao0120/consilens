package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.model.AgentModelClient;
import com.consilens.agent.api.model.AgentModelEventListener;
import com.consilens.agent.api.model.AgentModelFinishReason;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelResponse;
import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.core.runtime.AgentRunScheduler;
import com.consilens.agent.core.runtime.AgentRunWorker;
import com.consilens.agent.eval.ScriptedAgentModelClient;
import com.consilens.server.api.dto.ConnectionTestResponse;
import com.consilens.server.api.dto.ai.SendAgentMessageResponse;
import com.consilens.server.application.ai.AgentConversationApplicationService;
import com.consilens.server.application.ai.AgentSecretApplicationService;
import com.consilens.server.application.connection.ConnectionTestService;
import com.consilens.server.domain.repository.DataSourceRepository;
import com.consilens.server.domain.repository.TaskDefinitionRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Design 33.2 closed loop: a scripted model walks stage -> secret -> probe ->
 * compare draft -> prepare -> commit; approval then executes the plan
 * deterministically. Asserts 2 datasources + 1 task definition, zero
 * pre-approval writes, no secrets in events, and requestId replay idempotency.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "consilens.server.ai.enabled=true",
        "consilens.server.ai.fail-fast=false",
        "consilens.server.ai.api-key-env=CONSILENS_TEST_FAKE_KEY",
        "consilens.server.ai.secret-key-env=CONSILENS_TEST_FAKE_SECRET",
        "consilens.server.ai.secret-store=memory",
        "consilens.server.ai.poller-enabled=false",
        "consilens.server.ai.command-poll-interval-ms=3600000",
        "consilens.server.ai.lease-seconds=3600",
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "spring.sql.init.mode=always",
        "consilens.datasource.encryption-key="
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class AgentDatasourceJobClosedLoopIntegrationTest {

    private static final AtomicInteger CALL_COUNTER = new AtomicInteger();

    @TestConfiguration
    static class ScriptedModelConfig {
        @Bean
        @Primary
        AgentModelClient scriptedModelClient() {
            List<AgentModelResponse> script = List.of(
                    toolCall("stage_datasource_draft",
                            "{\"side\":\"SOURCE\",\"name\":\"prod_mysql\",\"type\":\"mysql\","
                                    + "\"nonSecretParams\":{\"host\":\"prod-db\",\"port\":3306,"
                                    + "\"database\":\"shop\",\"username\":\"ro\"}}"),
                    toolCall("stage_datasource_draft",
                            "{\"side\":\"TARGET\",\"name\":\"dw_starrocks\",\"type\":\"mysql\","
                                    + "\"nonSecretParams\":{\"host\":\"dw-db\",\"port\":9030,"
                                    + "\"database\":\"dw\",\"username\":\"etl\"}}"),
                    toolCall("probe_datasource", "{\"draftId\":\"draft_source\"}"),
                    toolCall("probe_datasource", "{\"draftId\":\"draft_target\"}"),
                    toolCall("stage_compare_definition",
                            "{\"taskName\":\"orders-prod-vs-dw\",\"sourceDraftId\":\"draft_source\","
                                    + "\"targetDraftId\":\"draft_target\","
                                    + "\"sourceDatabase\":\"shop\",\"sourceTable\":\"orders\","
                                    + "\"targetDatabase\":\"dw\",\"targetTable\":\"orders\","
                                    + "\"keys\":[\"order_id\"],\"ignoreColumns\":[\"ingest_time\"]}"),
                    toolCall("prepare_provisioning_plan", "{}"),
                    toolCall("commit_provisioning_plan", "{}"));
            ScriptedAgentModelClient delegate = new ScriptedAgentModelClient(script);
            return (AgentModelRequest request, AgentModelEventListener listener,
                    AgentCancellationToken cancellationToken) ->
                    delegate.complete(request, listener, cancellationToken);
        }
    }

    @Autowired
    private AgentConversationApplicationService conversationService;

    @Autowired
    private AgentSecretApplicationService secretApplicationService;

    @Autowired
    private AgentRunScheduler scheduler;

    @Autowired
    private MyBatisAgentPersistence persistence;

    @Autowired
    private DataSourceRepository dataSourceRepository;

    @Autowired
    private TaskDefinitionRepository taskDefinitionRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @MockBean
    private ConnectionTestService connectionTestService;

    private final AtomicReference<String> sessionId = new AtomicReference<>();

    @BeforeAll
    static void setSecretKey() {
        System.setProperty("CONSILENS_TEST_FAKE_SECRET",
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @AfterAll
    static void clearSecretKey() {
        System.clearProperty("CONSILENS_TEST_FAKE_SECRET");
    }

    @BeforeEach
    void setUp() {
        when(connectionTestService.test(any())).thenReturn(
                ConnectionTestResponse.builder().success(true).latencyMs(5L).build());
    }

    @Test
    void closedLoopCreatesResourcesAndReplayDoesNotDuplicate() {
        assertTrue(applicationContext.getBeansOfType(AgentRunPollingScheduler.class).isEmpty(),
                "poller must be disabled in tests");
        sessionId.set(conversationService.createSession("local", "create-closed-1", "闭环")
                .getId());
        assertNotNull(sessionId.get());

        conversationService.sendMessage(sessionId.get(), "local", "msg-1",
                "帮我创建生产 MySQL 和数仓 StarRocks 数据源，再建 orders 比对任务");
        assertTrue(persistence.findRunByRequestId(sessionId.get(), "msg-1").isPresent(),
                "message run must be queued");

        // 1) stage SOURCE -> SECRET_INPUT_REQUIRED
        processRun("msg-1");
        String sourceSecret = lastEventPayload("SECRET_INPUT_REQUIRED", "secretRequestId");
        assertNotNull(sourceSecret);
        assertTrue(secretApplicationService.submit(sessionId.get(), "local", sourceSecret,
                Map.of("password", "src-pw")));

        // 2) stage TARGET -> SECRET_INPUT_REQUIRED
        processRun("sec_" + sourceSecret);
        String targetSecret = lastEventPayload("SECRET_INPUT_REQUIRED", "secretRequestId");
        assertTrue(secretApplicationService.submit(sessionId.get(), "local", targetSecret,
                Map.of("password", "tgt-pw")));

        // 3) one resume run walks probe x2 -> compare -> prepare -> commit -> APPROVAL_REQUIRED
        processRun("sec_" + targetSecret);
        assertEquals(0, dataSourceRepository.listAll().size());
        String approvalId = lastEventPayload("APPROVAL_REQUIRED", "approvalId");
        String actionDigest = lastEventPayload("APPROVAL_REQUIRED", "actionDigest");
        assertNotNull(approvalId, "approval was not requested");
        AgentApprovalRecord approval = persistence.findApproval(approvalId).orElseThrow();

        conversationService.decideApproval(sessionId.get(), "local", approvalId, "dec-1",
                AgentApprovalDecision.APPROVE, actionDigest, approval.getVersion());

        // 4) approved plan executes without model turns
        processRun("plan_" + approval.getProposedRunId());

        assertEquals(2, dataSourceRepository.listAll().size());
        List<com.consilens.server.domain.model.TaskDefinitionRecord> definitions =
                taskDefinitionRepository.listPage(1, 100, null, null).getItems();
        assertEquals(1, definitions.size());
        String configJson = definitions.get(0).getConfig();
        assertTrue(configJson.contains("datasourceId"));
        assertFalse(configJson.contains("password"));
        assertFalse(allEventsText().contains("src-pw"));
        assertFalse(allEventsText().contains("tgt-pw"));
        assertEquals(AgentSessionStatus.READY,
                persistence.findSession(sessionId.get()).orElseThrow().getStatus());

        // 5) replaying the same requestId returns the existing run, no duplicates
        int datasources = dataSourceRepository.listAll().size();
        SendAgentMessageResponse replayed =
                conversationService.sendMessage(sessionId.get(), "local", "msg-1", "重复消息");
        assertEquals("ALREADY_PROCESSED", replayed.getStatus());
        assertEquals(datasources, dataSourceRepository.listAll().size());
        assertEquals(1, taskDefinitionRepository.listPage(1, 100, null, null).getItems().size());
    }

    private void processRun(String requestId) {
        AgentRunRecord run = persistence.findRunByRequestId(sessionId.get(), requestId)
                .orElseThrow(() -> new IllegalStateException("run not found: " + requestId));
        AgentRunWorker worker = scheduler.worker();
        worker.process(run, AgentCancellationToken.NEVER_CANCELLED);
    }

    private String lastEventPayload(String type, String field) {
        return persistence.listEventsAfter(sessionId.get(), -1, 1000).stream()
                .filter(e -> e.getType().name().equals(type))
                .reduce((first, second) -> second)
                .map(e -> e.getPayload().path(field).asText(null))
                .orElse(null);
    }

    private String allEventsText() {
        return persistence.listEventsAfter(sessionId.get(), -1, 1000).toString();
    }

    private static AgentModelResponse toolCall(String name, String args) {
        return AgentModelResponse.success(null,
                List.of(AgentModelToolCall.builder()
                        .id("call_" + name + "_" + CALL_COUNTER.incrementAndGet())
                        .name(name).arguments(args).index(0).build()),
                AgentModelFinishReason.TOOL_CALLS, null, true, 1);
    }
}
