package com.consilens.agent.core.state;

import com.consilens.agent.api.state.AgentDatasourceDraftState;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.state.AgentSecretStatus;
import com.consilens.agent.core.security.SensitiveValueGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Post-reduce invariants. Runs after every typed reducer inside the same
 * optimistic-lock transaction; a violation fails the run instead of
 * persisting an inconsistent state.
 */
public final class AgentStateInvariantValidator {

    private AgentStateInvariantValidator() {
    }

    public static List<String> validateTransition(AgentWorkingState previous, AgentWorkingState next) {
        List<String> violations = new ArrayList<>();
        if (!AgentStateMachine.canAdvance(previous.getStage(), next.getStage())) {
            violations.add("illegal stage transition: " + previous.getStage() + " -> " + next.getStage());
        }
        return violations;
    }

    public static List<String> validate(AgentWorkingState state) {
        List<String> violations = new ArrayList<>();
        if (state.getObjectiveId() == null || state.getObjectiveId().isBlank()) {
            violations.add("objectiveId must not be empty");
        }
        violations.addAll(validateDraft(state.getSource(), "source"));
        violations.addAll(validateDraft(state.getTarget(), "target"));
        return violations;
    }

    /**
     * Persisted resource IDs are immutable once assigned: a reducer may fill a
     * null id, but must never replace an existing id with a different value.
     */
    public static void assertResourceIdsStable(AgentWorkingState previous, AgentWorkingState next) {
        assertStable(previous == null ? null : previous.getSource(),
                next.getSource(), "source.datasourceId");
        assertStable(previous == null ? null : previous.getTarget(),
                next.getTarget(), "target.datasourceId");
        if (previous != null
                && previous.getTask() != null
                && next.getTask() != null
                && previous.getTask().getDefinitionId() != null
                && next.getTask().getDefinitionId() != null
                && !previous.getTask().getDefinitionId().equals(next.getTask().getDefinitionId())) {
            throw new IllegalStateException("task.definitionId must be stable once assigned");
        }
    }

    private static void assertStable(AgentDatasourceDraftState previous,
                                     AgentDatasourceDraftState current,
                                     String path) {
        String oldId = previous == null ? null : previous.getDatasourceId();
        String newId = current == null ? null : current.getDatasourceId();
        if (oldId != null && newId != null && !oldId.equals(newId)) {
            throw new IllegalStateException(path + " must be stable once assigned");
        }
    }

    private static List<String> validateDraft(AgentDatasourceDraftState draft, String side) {
        List<String> violations = new ArrayList<>();
        if (draft == null) {
            return violations;
        }
        if (draft.getNonSecretParams() != null) {
            List<String> sensitive = SensitiveValueGuard.findSensitivePaths(draft.getNonSecretParams());
            for (String path : sensitive) {
                violations.add(side + ".nonSecretParams." + path + " must not contain secret-shaped fields");
            }
        }
        if ((draft.getSecretStatus() == AgentSecretStatus.REQUESTED
                || draft.getSecretStatus() == AgentSecretStatus.PROVIDED)
                && (draft.getSecretRequestId() == null || draft.getSecretRequestId().isBlank())) {
            violations.add(side + ".secretRequestId is required when secretStatus="
                    + draft.getSecretStatus());
        }
        return violations;
    }

    /**
     * Convenience for reducers: merge new non-secret params into a draft map.
     * Unknown keys are rejected by the server-side form schema, not here.
     */
    public static Map<String, ?> requireNonSecret(Map<String, ?> params) {
        List<String> sensitive = SensitiveValueGuard.findSensitivePaths(params);
        if (!sensitive.isEmpty()) {
            throw new IllegalArgumentException("secret-shaped fields are not allowed in draft params: " + sensitive);
        }
        return params;
    }
}
