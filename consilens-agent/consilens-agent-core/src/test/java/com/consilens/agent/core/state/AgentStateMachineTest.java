package com.consilens.agent.core.state;

import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStateMachineTest {

    @Test
    void sessionTransitionsFollowTheStateDiagram() {
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.READY, AgentSessionStatus.RUNNING));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.RUNNING, AgentSessionStatus.WAITING_INPUT));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.RUNNING, AgentSessionStatus.WAITING_SECRET));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.RUNNING, AgentSessionStatus.WAITING_APPROVAL));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.WAITING_INPUT, AgentSessionStatus.RUNNING));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.WAITING_SECRET, AgentSessionStatus.RUNNING));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.WAITING_APPROVAL, AgentSessionStatus.RUNNING));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.WAITING_APPROVAL, AgentSessionStatus.READY));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.RUNNING, AgentSessionStatus.FAILED));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.RUNNING, AgentSessionStatus.CANCELLED));
        assertTrue(AgentStateMachine.canTransition(AgentSessionStatus.FAILED, AgentSessionStatus.RUNNING));
    }

    @Test
    void illegalSessionTransitionsAreRejected() {
        assertFalse(AgentStateMachine.canTransition(AgentSessionStatus.READY, AgentSessionStatus.WAITING_APPROVAL));
        assertFalse(AgentStateMachine.canTransition(AgentSessionStatus.WAITING_INPUT, AgentSessionStatus.READY));
        assertFalse(AgentStateMachine.canTransition(AgentSessionStatus.CANCELLED, AgentSessionStatus.RUNNING));
        assertFalse(AgentStateMachine.canTransition(AgentSessionStatus.FAILED, AgentSessionStatus.READY));
    }

    @Test
    void stageNeverMovesBackward() {
        assertFalse(AgentStateMachine.canAdvance(
                AgentWorkflowStage.DRAFTING_COMPARISON, AgentWorkflowStage.DISCOVERY));
        assertFalse(AgentStateMachine.canAdvance(
                AgentWorkflowStage.RESOURCES_READY, AgentWorkflowStage.PLAN_READY));
    }

    @Test
    void completedIsOnlyReachableFromResourcesReady() {
        assertFalse(AgentStateMachine.canAdvance(
                AgentWorkflowStage.DRAFTING_COMPARISON, AgentWorkflowStage.COMPLETED));
        assertFalse(AgentStateMachine.canAdvance(
                AgentWorkflowStage.DISCOVERING_METADATA, AgentWorkflowStage.COMPLETED));
        assertTrue(AgentStateMachine.canAdvance(
                AgentWorkflowStage.RESOURCES_READY, AgentWorkflowStage.COMPLETED));
    }

    @Test
    void commitChainIsEnforced() {
        assertTrue(AgentStateMachine.canAdvance(
                AgentWorkflowStage.PLAN_READY, AgentWorkflowStage.AWAITING_COMMIT_APPROVAL));
        assertFalse(AgentStateMachine.canAdvance(
                AgentWorkflowStage.DRAFTING_COMPARISON, AgentWorkflowStage.AWAITING_COMMIT_APPROVAL));
        assertFalse(AgentStateMachine.canAdvance(
                AgentWorkflowStage.PLAN_READY, AgentWorkflowStage.COMMITTING));
        assertTrue(AgentStateMachine.canAdvance(
                AgentWorkflowStage.AWAITING_COMMIT_APPROVAL, AgentWorkflowStage.COMMITTING));
        assertTrue(AgentStateMachine.canAdvance(
                AgentWorkflowStage.COMMITTING, AgentWorkflowStage.RESOURCES_READY));
    }

    @Test
    void multiHopForwardIsAllowedForFastPath() {
        assertTrue(AgentStateMachine.canAdvance(
                AgentWorkflowStage.DISCOVERY, AgentWorkflowStage.DRAFTING_COMPARISON));
        assertTrue(AgentStateMachine.canAdvance(
                AgentWorkflowStage.COLLECTING_DATASOURCES, AgentWorkflowStage.DISCOVERING_METADATA));
    }
}
