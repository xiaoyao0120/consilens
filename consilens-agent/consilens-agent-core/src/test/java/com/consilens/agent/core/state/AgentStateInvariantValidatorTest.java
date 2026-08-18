package com.consilens.agent.core.state;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStateInvariantValidatorTest {

    private AgentWorkingState base() {
        return AgentWorkingState.builder()
                .objectiveId("obj_1")
                .objective("create datasources")
                .stage(AgentWorkflowStage.DISCOVERY)
                .build();
    }

    @Test
    void rejectsIllegalStageJump() {
        AgentWorkingState next = base().withStage(AgentWorkflowStage.COMPLETED);
        assertTrue(AgentStateInvariantValidator.validateTransition(base(), next)
                .stream().anyMatch(v -> v.contains("illegal stage transition")));
    }

    @Test
    void rejectsSecretShapedFieldsInWorkingState() {
        Map<String, com.consilens.agent.api.state.AgentSlotValue> params = new LinkedHashMap<>();
        params.put("password", com.consilens.agent.api.state.AgentSlotValue.of("hunter2",
                com.consilens.agent.api.state.SlotSource.MODEL_INFERRED));
        AgentDatasourceDraftState source = AgentDatasourceDraftState.builder()
                .draftId("d1").name("n").type("mysql").nonSecretParams(params).build();
        AgentWorkingState state = base().toBuilder().source(source).build();

        assertTrue(AgentStateInvariantValidator.validate(state)
                .stream().anyMatch(v -> v.contains("must not contain secret-shaped fields")));
    }

    @Test
    void refusesToOverwritePersistedResourceId() {
        AgentDatasourceDraftState oldDraft = AgentDatasourceDraftState.builder()
                .draftId("d1").name("n").type("mysql").datasourceId("12").build();
        AgentDatasourceDraftState newDraft = AgentDatasourceDraftState.builder()
                .draftId("d1").name("n").type("mysql").datasourceId("99").build();
        AgentWorkingState previous = base().toBuilder().source(oldDraft).build();
        AgentWorkingState next = base().toBuilder().source(newDraft).build();

        assertThrows(IllegalStateException.class,
                () -> AgentStateInvariantValidator.assertResourceIdsStable(previous, next));
    }

    @Test
    void fillingNullIdIsAllowed() {
        AgentDatasourceDraftState oldDraft = AgentDatasourceDraftState.builder()
                .draftId("d1").name("n").type("mysql").build();
        AgentDatasourceDraftState newDraft = AgentDatasourceDraftState.builder()
                .draftId("d1").name("n").type("mysql").datasourceId("12").build();
        AgentWorkingState previous = base().toBuilder().source(oldDraft).build();
        AgentWorkingState next = base().toBuilder().source(newDraft).build();

        AgentStateInvariantValidator.assertResourceIdsStable(previous, next);
    }

    @Test
    void secretStatusRequiresSecretRequestId() {
        AgentDatasourceDraftState draft = AgentDatasourceDraftState.builder()
                .draftId("d1").name("n").type("mysql")
                .secretStatus(com.consilens.agent.api.state.AgentSecretStatus.REQUESTED)
                .build();
        AgentWorkingState state = base().toBuilder().source(draft).build();
        assertTrue(AgentStateInvariantValidator.validate(state)
                .stream().anyMatch(v -> v.contains("secretRequestId is required")));
    }
}
