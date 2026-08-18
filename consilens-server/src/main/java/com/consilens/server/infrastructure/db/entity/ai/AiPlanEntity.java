package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiPlanEntity {
    private String planId;
    private String sessionId;
    private String objectiveId;
    private String status;
    private String planDigest;
    private String configTemplateDigest;
    private String safeSummary;
    private String planJson;
    private Long version;
    private LocalDateTime createdAt;
}
