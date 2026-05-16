package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.session.model.PendingApprovalState;

import java.time.Instant;
import java.util.Locale;

/**
 * Minimal approval manager for run-style side effects.
 */
public class DefaultApprovalManager implements ApprovalManager {

    @Override
    public PendingApprovalState create(ActionPlan plan, String prompt) {
        return PendingApprovalState.builder()
                .type("EXECUTE_DIFF")
                .prompt(prompt)
                .commandName(plan.getCommandName())
                .commandArgument(plan.getCommandArgument())
                .createdAt(Instant.now())
                .build();
    }

    @Override
    public boolean isApprove(String input) {
        String normalized = normalize(input);
        return "approve execute".equals(normalized)
                || "/approve execute".equals(normalized);
    }

    @Override
    public boolean isDeny(String input) {
        String normalized = normalize(input);
        return "n".equals(normalized)
                || "no".equals(normalized)
                || "deny".equals(normalized)
                || "/deny".equals(normalized)
                || "cancel".equals(normalized)
                || "stop".equals(normalized)
                || "否".equals(normalized)
                || "取消".equals(normalized)
                || "停止".equals(normalized);
    }

    @Override
    public ActionPlan restore(String sessionId, PendingApprovalState pendingApproval) {
        return ActionPlan.builder()
                .sessionId(sessionId)
                .commandName(pendingApproval.getCommandName())
                .commandArgument(pendingApproval.getCommandArgument())
                .build();
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }
}
