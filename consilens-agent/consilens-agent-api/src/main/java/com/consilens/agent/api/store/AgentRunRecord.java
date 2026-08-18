package com.consilens.agent.api.store;

import com.consilens.agent.api.state.AgentRunStatus;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Instant;

@Value
@Builder
@With
public class AgentRunRecord {
    String runId;
    String sessionId;
    String requestId;
    String resumeFromRunId;
    AgentRunStatus status;
    String leaseOwner;
    Instant leaseExpiresAt;
    int turnCount;
    int toolCallCount;
    long tokenCount;
    String errorCode;
    Instant startedAt;
    Instant endedAt;
    long version;
}
