package com.consilens.agent.core.loop;

import com.consilens.agent.api.state.AgentWorkingState;

/**
 * Stop decisions are code-driven (section 20.5): cancellation, waiting for
 * approval/secret/input, unrecoverable error, budget, task completion, plain
 * answer. A model claiming "done" is never evidence of completion.
 */
public final class AgentStopEvaluator {

    public AgentStopDecision evaluate(String text, AgentWorkingState state) {
        if (state == null) {
            return AgentStopDecision.builder().reason(AgentStopReason.PLAIN_ANSWER).message(text).build();
        }
        if (state.getSource() != null
                && state.getSource().getSecretStatus() == com.consilens.agent.api.state.AgentSecretStatus.REQUESTED) {
            return AgentStopDecision.builder().reason(AgentStopReason.WAITING_SECRET).message(text).build();
        }
        if (state.getProvisioningPlan() != null
                && state.getProvisioningPlan().getStatus()
                == com.consilens.agent.api.plan.AgentPlanStatus.AWAITING_APPROVAL) {
            return AgentStopDecision.builder().reason(AgentStopReason.WAITING_APPROVAL).message(text).build();
        }
        if (!state.getUnresolvedQuestions().isEmpty()) {
            return AgentStopDecision.builder().reason(AgentStopReason.WAITING_INPUT).message(text).build();
        }
        return AgentStopDecision.builder().reason(AgentStopReason.TASK_COMPLETE).message(text).build();
    }
}
