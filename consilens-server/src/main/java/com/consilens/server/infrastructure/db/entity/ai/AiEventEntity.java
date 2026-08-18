package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiEventEntity {
    private Long id;
    private String eventId;
    private String sessionId;
    private Long seq;
    private String runId;
    private String turnId;
    private String eventType;
    private String visibility;
    private Integer schemaVersion;
    private String payload;
    private LocalDateTime createdAt;
}
