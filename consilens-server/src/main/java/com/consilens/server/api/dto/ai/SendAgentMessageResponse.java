package com.consilens.server.api.dto.ai;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SendAgentMessageResponse {
    String runId;
    String status;
    long lastSeq;
}
