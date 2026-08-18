package com.consilens.server.api.dto.ai;

import lombok.Data;

@Data
public class CreateAgentSessionRequest {
    private String requestId;
    private String title;
}
