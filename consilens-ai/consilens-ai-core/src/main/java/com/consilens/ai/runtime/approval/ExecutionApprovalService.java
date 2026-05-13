package com.consilens.ai.runtime.approval;

import com.consilens.ai.session.model.AiSession;

/**
 * Approval boundary for mutating or externally visible execution actions.
 */
public interface ExecutionApprovalService {

    boolean isApproved(AiSession session, String action, ApprovalMode mode, String approvalText);
}
