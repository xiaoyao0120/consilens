package com.consilens.server.application.ai;

import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentRunRecord;

import java.time.Instant;
import java.util.UUID;

/**
 * Creates QUEUED resume runs (secret submitted, approval decided, retry) for
 * the durable worker to claim. Idempotent by (sessionId, requestId).
 */
public class AgentRunEnqueueService {

    private final AgentPersistence persistence;

    public AgentRunEnqueueService(AgentPersistence persistence) {
        this.persistence = persistence;
    }

    public void enqueueResume(String sessionId, String requestId, String resumeFromRunId) {
        if (persistence.findRunByRequestId(sessionId, requestId).isPresent()) {
            return;
        }
        persistence.createRun(AgentRunRecord.builder()
                .runId("run_" + UUID.randomUUID())
                .sessionId(sessionId)
                .requestId(requestId)
                .resumeFromRunId(resumeFromRunId)
                .status(AgentRunStatus.QUEUED)
                .startedAt(Instant.now())
                .build());
    }
}
