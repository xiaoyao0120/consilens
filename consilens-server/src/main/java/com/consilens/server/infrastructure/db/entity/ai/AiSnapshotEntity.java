package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiSnapshotEntity {
    private String sessionId;
    private Long seq;
    private String workingState;
    private String conversationSummary;
    private Integer schemaVersion;
    private LocalDateTime createdAt;
}
