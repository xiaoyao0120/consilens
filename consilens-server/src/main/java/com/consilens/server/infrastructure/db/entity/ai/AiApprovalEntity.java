package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiApprovalEntity {
    private String approvalId;
    private String sessionId;
    private String proposedRunId;
    private String status;
    private String actionDigest;
    private String safeSummary;
    private String safeActionsJson;
    private String actorId;
    private LocalDateTime decidedAt;
    private LocalDateTime expiresAt;
    private Long version;
}
