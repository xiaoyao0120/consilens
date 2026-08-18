package com.consilens.agent.core.loop;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class AgentLoopRequest {
    String sessionId;
    String runId;
    String requestId;
    String actorId;
    String workerId;
    String objectiveId;
    String objective;
    String userText;
    AgentRunTrigger trigger;
    String resumeFromRunId;
    Instant deadline;
    @Builder.Default
    boolean resumeExistingRun = false;
}
