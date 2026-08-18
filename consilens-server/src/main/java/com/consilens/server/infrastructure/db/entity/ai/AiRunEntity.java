package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiRunEntity {
    private String runId;
    private String sessionId;
    private String requestId;
    private String resumeFromRunId;
    private String status;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private Integer turnCount;
    private Integer toolCallCount;
    private Long tokenCount;
    private String errorCode;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long version;
}
