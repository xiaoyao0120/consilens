package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * cs_ai_session: actor-bound session with optimistic-lock version and lease.
 */
@Data
public class AiSessionEntity {
    private String id;
    private String actorId;
    private String requestId;
    private String title;
    private String objective;
    private String status;
    private String workflowStage;
    private String activeRunId;
    private Long nextSeq;
    private Long snapshotSeq;
    private Long version;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
