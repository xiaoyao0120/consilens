package com.consilens.agent.api.store;

import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.time.Instant;

@Value
@Builder
@With
public class AgentSessionRecord {
    String id;
    String actorId;
    String requestId;
    String title;
    String objective;
    AgentSessionStatus status;
    AgentWorkflowStage workflowStage;
    String activeRunId;
    long nextSeq;
    long snapshotSeq;
    long version;
    String leaseOwner;
    Instant leaseExpiresAt;
    Instant createdAt;
    Instant updatedAt;
}
