package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.api.store.AgentSnapshotRecord;
import com.consilens.agent.core.loop.AgentLoopRequest;
import com.consilens.agent.core.loop.AgentLoopResult;
import com.consilens.agent.core.loop.AgentRunConfig;
import com.consilens.agent.core.loop.AgentRunTrigger;
import com.consilens.agent.core.loop.DefaultAgentLoop;
import com.consilens.agent.core.runtime.AgentRunWorker;
import com.consilens.agent.core.loop.AgentLoopOutcome;
import com.consilens.server.application.ai.AgentRunEnqueueService;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.server.application.ai.plan.ProvisioningPlanExecutor;
import com.consilens.server.application.ai.plan.RunTaskExecutor;
import com.consilens.server.boot.ConsilensServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Executes one claimed run through the durable loop. The loop re-acquires the
 * session lease held by this worker and refreshes the run lease each turn.
 */
public class ServerAgentRunWorker implements AgentRunWorker {

    private static final Logger log = LoggerFactory.getLogger(ServerAgentRunWorker.class);

    private final MyBatisAgentPersistence persistence;
    private final DefaultAgentLoop loop;
    private final AgentRunConfig runConfig;
    private final ConsilensServerProperties properties;
    private final ProvisioningPlanExecutor planExecutor;
    private final RunTaskExecutor runTaskExecutor;
    private final AgentRunEnqueueService runEnqueueService;

    public ServerAgentRunWorker(MyBatisAgentPersistence persistence,
                                DefaultAgentLoop loop,
                                AgentRunConfig runConfig,
                                ConsilensServerProperties properties,
                                ProvisioningPlanExecutor planExecutor,
                                RunTaskExecutor runTaskExecutor,
                                AgentRunEnqueueService runEnqueueService) {
        this.persistence = persistence;
        this.loop = loop;
        this.runConfig = runConfig;
        this.properties = properties;
        this.planExecutor = planExecutor;
        this.runTaskExecutor = runTaskExecutor;
        this.runEnqueueService = runEnqueueService;
    }

    @Override
    public void process(AgentRunRecord run, AgentCancellationToken cancellationToken) {
        long started = System.currentTimeMillis();
        log.info("agent run processing: sessionId={} runId={} requestId={} resumeFrom={} status={}",
                run.getSessionId(), run.getRunId(), run.getRequestId(),
                run.getResumeFromRunId(), run.getStatus());
        AgentSessionRecord session = persistence.findSession(run.getSessionId()).orElse(null);
        if (session == null) {
            return;
        }
        Optional<AgentSnapshotRecord> latest = persistence.latestSnapshot(run.getSessionId());
        String objectiveId = latest.map(s -> s.getWorkingState().getObjectiveId())
                .orElse("obj_" + run.getSessionId());
        AgentLoopRequest request = AgentLoopRequest.builder()
                .sessionId(run.getSessionId())
                .runId(run.getRunId())
                .requestId(run.getRequestId())
                .actorId(session.getActorId())
                .workerId(run.getLeaseOwner() == null ? "worker" : run.getLeaseOwner())
                .objectiveId(objectiveId)
                .objective(session.getObjective())
                .trigger(AgentRunTrigger.USER_MESSAGE)
                .resumeFromRunId(run.getResumeFromRunId())
                .deadline(Instant.now().plus(runConfig.getRunTimeout()))
                .resumeExistingRun(true)
                .build();
        AgentLoopResult result;
        if (run.getResumeFromRunId() != null && run.getResumeFromRunId().startsWith("run:")) {
            String approvalId = run.getResumeFromRunId().substring("run:".length());
            String definitionId = persistence.findApproval(approvalId)
                    .map(a -> a.getSafeActionsJson())
                    .orElse(null);
            long parsed = definitionId == null ? -1 : parseDefinitionId(definitionId);
            result = parsed < 0
                    ? AgentLoopResult.builder().outcome(com.consilens.agent.core.loop.AgentLoopOutcome.FAILED)
                    .sessionStatus(AgentSessionStatus.FAILED)
                    .runStatus(AgentRunStatus.FAILED).errorCode("APPROVAL_NOT_FOUND").build()
                    : runTaskExecutor.execute(run.getSessionId(), session.getActorId(), parsed,
                    run.getLeaseOwner() == null ? "worker" : run.getLeaseOwner(),
                    run.getRequestId());
        } else if (run.getResumeFromRunId() != null && run.getResumeFromRunId().startsWith("plan:")) {
            String planId = run.getResumeFromRunId().substring("plan:".length());
            result = planExecutor.execute(run.getSessionId(), session.getActorId(), planId,
                    run.getLeaseOwner() == null ? "worker" : run.getLeaseOwner());
        } else {
            result = loop.run(request, cancellationToken);
        }
        log.info("agent run processed: sessionId={} runId={} outcome={} sessionStatus={} "
                        + "errorCode={} retryable={} durationMs={}",
                run.getSessionId(), run.getRunId(), result.getOutcome(),
                result.getSessionStatus(), result.getErrorCode(), result.isRetryable(),
                System.currentTimeMillis() - started);
        if (result.isRetryable() && result.getErrorCode() != null) {
            // Retryable failures stay queued for the recovery scanner.
            persistence.findRun(run.getRunId()).ifPresent(current ->
                    persistence.updateRun(current.withErrorCode(result.getErrorCode())));
        }
        if (result.getOutcome() == AgentLoopOutcome.COMPLETED) {
            consumePendingSteering(run.getSessionId(), run.getRequestId());
        }
    }

    /**
     * RUNNING 期间排队（STEERING_QUEUED）的消息在 run 结束后成为 follow-up run。
     */
    private void consumePendingSteering(String sessionId, String completedRunRequestId) {
        persistence.listEventsAfter(sessionId, -1, 500).stream()
                .filter(e -> e.getType() == com.consilens.agent.api.event.AgentEventType.STEERING_QUEUED)
                .map(e -> e.getPayload().path("requestId").asText(null))
                .filter(rid -> rid != null
                        && persistence.findRunByRequestId(sessionId, rid).isEmpty())
                .forEach(rid -> runEnqueueService.enqueueResume(sessionId, rid, null));
    }

    private static long parseDefinitionId(String safeActionsJson) {
        if (safeActionsJson == null) {
            return -1;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(safeActionsJson).path("definitionId").asLong(-1);
        } catch (Exception e) {
            return -1;
        }
    }
}
