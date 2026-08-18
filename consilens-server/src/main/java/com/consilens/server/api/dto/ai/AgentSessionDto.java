package com.consilens.server.api.dto.ai;

import com.consilens.agent.api.state.AgentWorkingState;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AgentSessionDto {
    String id;
    String status;
    long lastSeq;
    AgentWorkingState workingState;
}
