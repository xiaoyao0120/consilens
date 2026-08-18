package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiPlanActionEntity {
    private String planId;
    private String actionId;
    private Integer sequence;
    private String actionType;
    private String name;
    private String safeArgsJson;
    private String status;
    private String idempotencyKey;
    private String inputDigest;
    private String resourceType;
    private String resourceId;
    private String resultDigest;
    private String errorCode;
    private Boolean retryable;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
