package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.core.runtime.AgentRunScheduler;
import com.consilens.agent.core.runtime.AgentRunWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Optional;

/**
 * Polls the durable run queue and executes claimed runs through the worker.
 * Runs are executed inline on the poll thread for now; concurrency scaling is
 * governed by max-concurrent-runs when the poller moves to a pool.
 */
@Slf4j
public class AgentRunPollingScheduler {

    private final AgentRunScheduler scheduler;
    private final String workerId;

    public AgentRunPollingScheduler(AgentRunScheduler scheduler, String workerId) {
        this.scheduler = scheduler;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${consilens.server.ai.command-poll-interval-ms:1000}")
    public void poll() {
        Optional<AgentRunRecord> run = scheduler.claimNextRun(workerId, Instant.now());
        if (run.isPresent()) {
            AgentRunWorker worker = scheduler.worker();
            try {
                worker.process(run.get(), AgentCancellationToken.NEVER_CANCELLED);
            } catch (RuntimeException e) {
                log.error("agent run {} failed unexpectedly", run.get().getRunId(), e);
            } finally {
                scheduler.releaseRun(run.get().getRunId(), workerId);
            }
        }
    }
}
