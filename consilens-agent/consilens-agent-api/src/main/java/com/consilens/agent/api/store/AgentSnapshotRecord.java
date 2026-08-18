package com.consilens.agent.api.store;

import com.consilens.agent.api.state.AgentWorkingState;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class AgentSnapshotRecord {
    String sessionId;
    long seq;
    AgentWorkingState workingState;
    String conversationSummary;
    int schemaVersion;
    Instant createdAt;
}
