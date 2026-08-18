package com.consilens.agent.core.state;

import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic state machine for session status and workflow stage.
 * Transitions are decided by code only; model text can never trigger one.
 */
public final class AgentStateMachine {

    private static final Map<AgentSessionStatus, Set<AgentSessionStatus>> SESSION_TRANSITIONS =
            buildSessionTransitions();

    private static final Map<AgentWorkflowStage, Integer> STAGE_ORDER = new EnumMap<>(AgentWorkflowStage.class);

    static {
        int order = 0;
        for (AgentWorkflowStage stage : AgentWorkflowStage.values()) {
            STAGE_ORDER.put(stage, order++);
        }
    }

    private AgentStateMachine() {
    }

    public static boolean canTransition(AgentSessionStatus from, AgentSessionStatus to) {
        Set<AgentSessionStatus> allowed = SESSION_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Workflow stages only move forward. Hard guardrails: COMPLETED is only
     * reachable from RESOURCES_READY; COMMIT_* stages only follow the plan
     * chain. Forward multi-hop is allowed for the "everything provided at
     * once" path (e.g. DISCOVERY -> DRAFTING_COMPARISON).
     */
    public static boolean canAdvance(AgentWorkflowStage from, AgentWorkflowStage to) {
        if (from == to) {
            return true;
        }
        if (STAGE_ORDER.get(to) <= STAGE_ORDER.get(from)) {
            return false;
        }
        switch (to) {
            case AWAITING_COMMIT_APPROVAL:
                return from == AgentWorkflowStage.PLAN_READY;
            case COMMITTING:
                return from == AgentWorkflowStage.AWAITING_COMMIT_APPROVAL;
            case RESOURCES_READY:
                return from == AgentWorkflowStage.COMMITTING;
            case COMPLETED:
                return from == AgentWorkflowStage.RESOURCES_READY;
            default:
                return true;
        }
    }

    private static Map<AgentSessionStatus, Set<AgentSessionStatus>> buildSessionTransitions() {
        Map<AgentSessionStatus, Set<AgentSessionStatus>> map = new EnumMap<>(AgentSessionStatus.class);
        map.put(AgentSessionStatus.READY, EnumSet.of(
                AgentSessionStatus.RUNNING,
                AgentSessionStatus.FAILED,
                AgentSessionStatus.ARCHIVED));
        map.put(AgentSessionStatus.RUNNING, EnumSet.of(
                AgentSessionStatus.WAITING_INPUT,
                AgentSessionStatus.WAITING_SECRET,
                AgentSessionStatus.WAITING_APPROVAL,
                AgentSessionStatus.READY,
                AgentSessionStatus.FAILED,
                AgentSessionStatus.CANCELLED));
        map.put(AgentSessionStatus.WAITING_INPUT, EnumSet.of(
                AgentSessionStatus.RUNNING,
                AgentSessionStatus.CANCELLED));
        map.put(AgentSessionStatus.WAITING_SECRET, EnumSet.of(
                AgentSessionStatus.RUNNING,
                AgentSessionStatus.CANCELLED));
        map.put(AgentSessionStatus.WAITING_APPROVAL, EnumSet.of(
                AgentSessionStatus.RUNNING,
                AgentSessionStatus.READY,
                AgentSessionStatus.CANCELLED));
        map.put(AgentSessionStatus.FAILED, EnumSet.of(AgentSessionStatus.RUNNING));
        map.put(AgentSessionStatus.CANCELLED, EnumSet.noneOf(AgentSessionStatus.class));
        map.put(AgentSessionStatus.ARCHIVED, EnumSet.noneOf(AgentSessionStatus.class));
        return map;
    }
}
