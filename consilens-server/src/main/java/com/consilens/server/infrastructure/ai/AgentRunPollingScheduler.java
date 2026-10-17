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
 * Runs are executed inline on the scheduled poll thread, so this poller
 * processes at most one run at a time; {@code ai.max-concurrent-runs} is not a
 * parallelism knob for this loop (it currently only sizes recovery batches and
 * documents the intended ceiling). Move execution to a pool before relying on
 * that setting for parallel runs.
 */
@Slf4j
public class AgentRunPollingScheduler {

    private final AgentRunScheduler scheduler;
    private final String workerId;

    public AgentRunPollingScheduler(AgentRunScheduler scheduler, String workerId) {
        this.scheduler = scheduler;
        this.workerId = workerId;
    }

    @Scheduled(fixedDelayString = "${consilens.server.scheduler.command-poll-interval-ms:1000}")
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
