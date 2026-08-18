package com.consilens.server.application.ai.tool;

import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentComparisonDraft;
import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentProbeStatus;
import com.consilens.agent.api.state.AgentSecretStatus;
import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.AgentTaskDraftState;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.state.SlotSource;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.store.InMemoryAgentPersistence;
import com.consilens.server.application.ai.approval.AgentApprovalService;
import com.consilens.server.application.ai.plan.ProvisioningPlanCompiler;
import com.consilens.server.application.ai.tool.dto.CommitProvisioningPlanInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitProvisioningPlanToolTest {

    private InMemoryAgentPersistence persistence;
    private ProvisioningPlanCompiler compiler;
    private CommitProvisioningPlanTool tool;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryAgentPersistence();
        persistence.createSession(AgentSessionRecord.builder()
                .id("s1").actorId("actor").requestId("create-1")
                .status(com.consilens.agent.api.state.AgentSessionStatus.WAITING_APPROVAL)
                .workflowStage(AgentWorkflowStage.PLAN_READY)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
        compiler = new ProvisioningPlanCompiler();
        tool = new CommitProvisioningPlanTool(persistence,
                new AgentApprovalService(persistence), compiler);
    }

    private AgentWorkingState state(String host) {
        Map<String, AgentSlotValue> sourceParams = new LinkedHashMap<>();
        sourceParams.put("host", AgentSlotValue.of(host, SlotSource.USER_CONFIRMED));
        Map<String, AgentSlotValue> targetParams = new LinkedHashMap<>();
        targetParams.put("host", AgentSlotValue.of("dw-db", SlotSource.USER_CONFIRMED));
        return AgentWorkingState.builder()
                .objectiveId("obj-1").objective("obj")
                .stage(AgentWorkflowStage.PLAN_READY)
                .source(AgentDatasourceDraftState.builder()
                        .draftId("draft_source").name("prod").type("mysql")
                        .nonSecretParams(sourceParams)
                        .secretStatus(AgentSecretStatus.PROVIDED).secretRequestId("sec-1")
                        .probeStatus(AgentProbeStatus.SUCCEEDED)
                        .database("shop").table("orders")
                        .build())
                .target(AgentDatasourceDraftState.builder()
                        .draftId("draft_target").name("dw").type("mysql")
                        .nonSecretParams(targetParams)
                        .secretStatus(AgentSecretStatus.PROVIDED).secretRequestId("sec-2")
                        .probeStatus(AgentProbeStatus.SUCCEEDED)
                        .database("dw").table("orders")
                        .build())
                .comparison(AgentComparisonDraft.builder()
                        .keys(List.of("order_id")).ignoreColumns(List.of("ingest_time")).build())
                .task(AgentTaskDraftState.builder().name("orders-vs-dw").build())
                .build();
    }

    @Test
    void commitsWhenDigestMatches() {
        AgentWorkingState state = state("prod-db");
        AgentProvisioningPlan plan = compiler.compile(state);
        persistence.saveReadyPlan("s1", plan.withSessionId("s1")
                .withStatus(com.consilens.agent.api.plan.AgentPlanStatus.PREPARED),
                persistence.findSession("s1").orElseThrow().getVersion());

        var outcome = tool.execute(CommitProvisioningPlanInput.builder().planId(plan.getPlanId()).build(),
                new DefaultTestToolContext("actor", "s1", state));

        assertTrue(outcome.isSuccess());
        assertTrue(outcome.isApprovalRequired());
        assertFalse(outcome.getApprovalId() == null);
    }

    @Test
    void parameterChangeInvalidatesTheApproval() {
        AgentWorkingState state = state("prod-db");
        AgentProvisioningPlan plan = compiler.compile(state);
        persistence.saveReadyPlan("s1", plan.withSessionId("s1")
                .withStatus(com.consilens.agent.api.plan.AgentPlanStatus.PREPARED),
                persistence.findSession("s1").orElseThrow().getVersion());
        AgentWorkingState mutated = state("prod-db-evil");

        var outcome = tool.execute(CommitProvisioningPlanInput.builder().planId(plan.getPlanId()).build(),
                new DefaultTestToolContext("actor", "s1", mutated));

        assertFalse(outcome.isSuccess());
        assertEquals("PLAN_DIGEST_MISMATCH", outcome.getErrorCode());
    }
}
