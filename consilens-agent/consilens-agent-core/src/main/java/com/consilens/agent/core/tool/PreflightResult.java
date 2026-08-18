package com.consilens.agent.core.tool;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PreflightResult {
    PreflightStatus status;
    PreparedToolCall prepared;
    String errorCode;
    String safeMessage;
    boolean reused;
}
