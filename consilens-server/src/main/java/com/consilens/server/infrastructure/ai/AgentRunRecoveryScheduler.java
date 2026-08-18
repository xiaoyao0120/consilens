package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.core.runtime.AgentRecoveryScanner;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.infrastructure.db.entity.ai.AiRunEntity;
import com.consilens.server.infrastructure.db.mapper.ai.AiRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Periodically recovers runs whose worker lease expired (crashed worker):
 * INTERRUPTED first, then re-queued for a fresh claim. Unknown write results
 * must be verified via idempotency keys before replay; WP-11 wires that check.
 */
@Slf4j
public class AgentRunRecoveryScheduler implements AgentRecoveryScanner {

    private final AiRunMapper runMapper;
    private final MyBatisAgentPersistence persistence;
    private final ConsilensServerProperties properties;

    public AgentRunRecoveryScheduler(AiRunMapper runMapper,
                                     MyBatisAgentPersistence persistence,
                                     ConsilensServerProperties properties) {
        this.runMapper = runMapper;
        this.persistence = persistence;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${consilens.server.ai.lease-seconds:30}000")
    public int scanAndRecover() {
        int leaseSeconds = properties.getAi().getLeaseSeconds();
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        int recovered = 0;
        List<AiRunEntity> stale = runMapper.listExpiredRunning(now,
                properties.getAi().getMaxConcurrentRuns() * 4);
        for (AiRunEntity run : stale) {
            if (runMapper.transitionStatus(run.getRunId(), AgentRunStatus.RUNNING.name(),
                    AgentRunStatus.INTERRUPTED.name()) != 1) {
                continue;
            }
            if (run.getLeaseOwner() != null) {
                persistence.releaseLease(run.getSessionId(), run.getLeaseOwner());
            }
            if (runMapper.transitionStatus(run.getRunId(), AgentRunStatus.INTERRUPTED.name(),
                    AgentRunStatus.QUEUED.name()) == 1) {
                recovered++;
                AgentRunRecord record = persistence.findRun(run.getRunId()).orElse(null);
                if (record != null) {
                    persistence.updateRun(record.withErrorCode("RUN_INTERRUPTED"));
                }
            }
        }
        if (recovered > 0) {
            log.warn("AI agent recovery: interrupted {} stale runs", recovered);
        }
        return recovered;
    }
}
