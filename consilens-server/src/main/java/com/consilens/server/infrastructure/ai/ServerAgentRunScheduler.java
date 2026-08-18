package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.runtime.AgentRunScheduler;
import com.consilens.agent.core.runtime.AgentRunWorker;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.infrastructure.db.entity.ai.AiRunEntity;
import com.consilens.server.infrastructure.db.mapper.ai.AiRunMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Database-backed run claim: atomic QUEUED -> RUNNING claim on the run row
 * plus the session lease that guarantees one active run per session.
 */
public class ServerAgentRunScheduler implements AgentRunScheduler {

    private static final int CLAIM_ATTEMPTS = 10;

    private final AiRunMapper runMapper;
    private final MyBatisAgentPersistence persistence;
    private final ConsilensServerProperties properties;
    private final AgentRunWorker worker;

    public ServerAgentRunScheduler(AiRunMapper runMapper,
                                   MyBatisAgentPersistence persistence,
                                   ConsilensServerProperties properties,
                                   AgentRunWorker worker) {
        this.runMapper = runMapper;
        this.persistence = persistence;
        this.properties = properties;
        this.worker = worker;
    }

    @Override
    public Optional<AgentRunRecord> claimNextRun(String workerId, Instant now) {
        int leaseSeconds = properties.getAi().getLeaseSeconds();
        LocalDateTime localNow = toLocal(now);
        LocalDateTime expiresAt = localNow.plusSeconds(leaseSeconds);
        List<AiRunEntity> candidates = runMapper.listQueuedWithFreeLease(localNow, CLAIM_ATTEMPTS);
        for (AiRunEntity candidate : candidates) {
            int claimed = runMapper.claimRun(candidate.getRunId(), workerId, localNow, expiresAt);
            if (claimed != 1) {
                continue;
            }
            AgentSessionRecord session = persistence.findSession(candidate.getSessionId()).orElse(null);
            if (session == null) {
                runMapper.releaseClaim(candidate.getRunId(), workerId);
                continue;
            }
            if (!persistence.tryAcquireLease(session.getId(), session.getActorId(),
                    workerId, now, now.plusSeconds(leaseSeconds))) {
                runMapper.releaseClaim(candidate.getRunId(), workerId);
                continue;
            }
            return persistence.findRun(candidate.getRunId())
                    .map(run -> run.withStatus(AgentRunStatus.RUNNING));
        }
        return Optional.empty();
    }

    @Override
    public boolean renewRun(String runId, String workerId, long expectedVersion, Instant expiresAt) {
        return runMapper.renewLease(runId, workerId, expectedVersion, toLocal(expiresAt)) == 1;
    }

    @Override
    public void releaseRun(String runId, String workerId) {
        runMapper.releaseRun(runId, workerId);
    }

    @Override
    public AgentRunWorker worker() {
        return worker;
    }

    private static LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
