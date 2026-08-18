package com.consilens.agent.core.runtime;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.store.AgentRunRecord;

import java.time.Instant;
import java.util.Optional;

/**
 * Durable run claim/lease contract (section 26.2). Implementations must use
 * database CAS, never JVM locks; the server provides the MyBatis-backed
 * implementation in WP-06.
 */
public interface AgentRunScheduler {

    Optional<AgentRunRecord> claimNextRun(String workerId, Instant now);

    boolean renewRun(String runId, String workerId, long expectedVersion, Instant expiresAt);

    void releaseRun(String runId, String workerId);

    AgentRunWorker worker();
}
