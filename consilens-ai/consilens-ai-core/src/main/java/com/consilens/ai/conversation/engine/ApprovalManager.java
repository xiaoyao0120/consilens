package com.consilens.ai.conversation.engine;

import com.consilens.ai.conversation.engine.model.ActionPlan;
import com.consilens.ai.session.model.PendingApprovalState;

/**
 * Owns approval parsing and persistence payload creation.
 */
public interface ApprovalManager {

    PendingApprovalState create(ActionPlan plan, String prompt);

    boolean isApprove(String input);

    boolean isDeny(String input);

    ActionPlan restore(String sessionId, PendingApprovalState pendingApproval);
}
