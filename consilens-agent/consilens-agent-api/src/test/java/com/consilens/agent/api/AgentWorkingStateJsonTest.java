package com.consilens.agent.api;

import com.consilens.agent.api.plan.AgentPlanStatus;
import com.consilens.agent.api.state.AgentComparisonDraft;
import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentPlanStateRef;
import com.consilens.agent.api.state.AgentProbeStatus;
import com.consilens.agent.api.state.AgentSecretStatus;
import com.consilens.agent.api.state.AgentSlotValue;
import com.consilens.agent.api.state.AgentTaskDraftState;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.state.SlotSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentWorkingStateJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AgentWorkingState sample() {
        Map<String, AgentSlotValue> params = new LinkedHashMap<>();
        params.put("host", AgentSlotValue.of("prod-db", SlotSource.USER_CONFIRMED));
        params.put("port", AgentSlotValue.of(3306, SlotSource.SYSTEM_DEFAULT));

        AgentDatasourceDraftState source = AgentDatasourceDraftState.builder()
                .draftId("draft_source")
                .name("prod_mysql")
                .type("mysql")
                .nonSecretParams(params)
                .secretStatus(AgentSecretStatus.PROVIDED)
                .probeStatus(AgentProbeStatus.SUCCEEDED)
                .database("shop")
                .table("orders")
                .columnsRef("event:82")
                .build();

        return AgentWorkingState.builder()
                .objectiveId("obj_uuid")
                .objective("创建两个数据源并建立 orders 比对任务")
                .stage(AgentWorkflowStage.DISCOVERING_METADATA)
                .source(source)
                .comparison(AgentComparisonDraft.builder()
                        .keys(List.of("order_id"))
                        .ignoreColumns(List.of("ingest_time"))
                        .build())
                .task(AgentTaskDraftState.builder().name("orders-prod-vs-dw").build())
                .provisioningPlan(AgentPlanStateRef.builder().status(AgentPlanStatus.DRAFT).build())
                .confirmedAssumptions(List.of("amount 类型兼容"))
                .resourceVersions(Map.of("datasourceSchema", "1"))
                .lastCompletedStep("PROBE_SOURCE")
                .build();
    }

    @Test
    void roundTripsWorkingStateWithoutLoss() throws Exception {
        AgentWorkingState original = sample();
        String json = mapper.writeValueAsString(original);
        AgentWorkingState restored = mapper.readValue(json, AgentWorkingState.class);

        assertEquals(original.getObjectiveId(), restored.getObjectiveId());
        assertEquals(original.getStage(), restored.getStage());
        assertEquals(original.getSource().getDraftId(), restored.getSource().getDraftId());
        assertEquals(original.getSource().getProbeStatus(), restored.getSource().getProbeStatus());
        assertEquals(original.getSource().getColumnsRef(), restored.getSource().getColumnsRef());
        assertEquals("prod-db", restored.getSource().getNonSecretParams().get("host").getValue());
        assertEquals(SlotSource.SYSTEM_DEFAULT, restored.getSource().getNonSecretParams().get("port").getSource());
        assertEquals(original.getComparison().getKeys(), restored.getComparison().getKeys());
        assertEquals(original.getConfirmedAssumptions(), restored.getConfirmedAssumptions());
        assertEquals(original.getResourceVersions(), restored.getResourceVersions());
    }

    @Test
    void builderDefensivelyCopiesCollections() {
        List<String> assumptions = new ArrayList<>();
        assumptions.add("a");
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("k", "v");

        AgentWorkingState state = AgentWorkingState.builder()
                .confirmedAssumptions(assumptions)
                .resourceVersions(versions)
                .build();

        assumptions.add("mutated");
        versions.put("k2", "v2");

        assertEquals(1, state.getConfirmedAssumptions().size());
        assertEquals(1, state.getResourceVersions().size());
        assertThrows(UnsupportedOperationException.class, () -> state.getConfirmedAssumptions().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> state.getResourceVersions().put("y", "z"));
    }

    @Test
    void toBuilderPreservesStateAndAllowsStageChange() {
        AgentWorkingState state = sample().withStage(AgentWorkflowStage.PLAN_READY);
        assertEquals(AgentWorkflowStage.PLAN_READY, state.getStage());
        assertEquals("obj_uuid", state.getObjectiveId());
        assertEquals("draft_source", state.getSource().getDraftId());
    }
}
