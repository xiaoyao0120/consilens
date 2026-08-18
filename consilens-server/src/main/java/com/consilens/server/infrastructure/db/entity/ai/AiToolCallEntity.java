package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiToolCallEntity {
    private String callId;
    private String sessionId;
    private String runId;
    private String turnId;
    private Integer sourceOrder;
    private String toolName;
    private String status;
    private String riskLevel;
    private String redactedArgs;
    private String argsDigest;
    private String idempotencyKey;
    private String actionDigest;
    private Long resultEventSeq;
    private String errorCode;
    private Boolean retryable;
    private String resultSummary;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long version;
}
