package com.consilens.server.api.dto.ai;

import lombok.Data;

@Data
public class SendAgentMessageRequest {
    private String requestId;
    private String text;
    private Long expectedLastSeq;
}
