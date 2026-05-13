package com.consilens.cli.ai.runtime;

import com.consilens.ai.runtime.approval.ApprovalMode;
import com.consilens.ai.runtime.approval.ExecutionApprovalService;
import com.consilens.ai.session.model.AiSession;

/**
 * Approval policy used by the CLI runtime.
 */
public class DefaultExecutionApprovalService implements ExecutionApprovalService {

    @Override
    public boolean isApproved(AiSession session, String action, ApprovalMode mode, String approvalText) {
        if (action == null || action.trim().isEmpty()) {
            return false;
        }
        String normalizedAction = action.trim().toLowerCase();
        String normalizedApproval = approvalText == null ? "" : approvalText.trim().toLowerCase();
        if (mode == ApprovalMode.EXPLICIT_FLAG) {
            return normalizedAction.equals(normalizedApproval);
        }
        return ("approve " + normalizedAction).equals(normalizedApproval) || normalizedAction.equals(normalizedApproval);
    }
}
