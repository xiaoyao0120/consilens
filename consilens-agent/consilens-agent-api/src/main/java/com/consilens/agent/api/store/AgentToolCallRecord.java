package com.consilens.agent.api.store;

import com.consilens.agent.api.tool.AgentToolCallStatus;
import com.consilens.agent.api.tool.ToolRiskLevel;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Instant;

/**
 * Persisted tool call. {@code redactedArgs} never contains secret values.
 */
@Value
@Builder
@With
public class AgentToolCallRecord {
    String callId;
    String sessionId;
    String runId;
    String turnId;
    int sourceOrder;
    String toolName;
    AgentToolCallStatus status;
    ToolRiskLevel riskLevel;
    String redactedArgs;
    String argsDigest;
    String idempotencyKey;
    String actionDigest;
    Long resultEventSeq;
    String errorCode;
    boolean retryable;
    String resultSummary;
    Instant startedAt;
    Instant endedAt;
    long version;
}
