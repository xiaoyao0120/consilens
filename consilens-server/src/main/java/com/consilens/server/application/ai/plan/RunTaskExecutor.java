package com.consilens.server.application.ai.plan;

import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.agent.core.loop.AgentLoopOutcome;
import com.consilens.agent.core.loop.AgentLoopResult;
import com.consilens.server.api.dto.TaskAcceptedResponse;
import com.consilens.server.api.dto.TaskDefinitionRunRequest;
import com.consilens.server.application.taskdefinition.TaskDefinitionService;

import java.time.Instant;

/**
 * Executes an approved run_task_definition: submits through the production
 * TaskDefinitionService and reports the accepted task id.
 */
public class RunTaskExecutor {

    private final AgentPersistence persistence;
    private final TaskDefinitionService taskDefinitionService;

    public RunTaskExecutor(AgentPersistence persistence,
                           TaskDefinitionService taskDefinitionService) {
        this.persistence = persistence;
        this.taskDefinitionService = taskDefinitionService;
    }

    public AgentLoopResult execute(String sessionId, String actorId, long definitionId,
                                   String workerId, String runRequestId) {
        Instant now = Instant.now();
        if (!persistence.tryAcquireLease(sessionId, actorId, workerId, now, now.plusSeconds(120))) {
            return result(AgentLoopOutcome.BUSY, AgentSessionStatus.RUNNING, "SESSION_BUSY", false);
        }
        try {
            long seq = persistence.nextSeq(sessionId);
            seq = append(seq, sessionId, AgentEventType.RUN_STARTED, AgentEventVisibility.AUDIT_ONLY,
                    AgentEventFactory.payload().put("workerId", workerId).put("resumeFromRunId", "run"));
            TaskAcceptedResponse accepted;
            try {
                TaskDefinitionRunRequest runRequest = new TaskDefinitionRunRequest();
                accepted = taskDefinitionService.run(definitionId, runRequest, "agent-" + workerId);
            } catch (Exception e) {
                seq = append(seq, sessionId, AgentEventType.RUN_FAILED,
                        AgentEventVisibility.USER_AND_MODEL,
                        AgentEventFactory.payload().put("errorCode", "TASK_RUN_FAILED")
                                .put("retryable", true).put("safeMessage", "任务提交失败"));
                persistence.findRunByRequestId(sessionId, runRequestId).ifPresent(run ->
                        persistence.updateRun(run.withStatus(AgentRunStatus.FAILED)
                                .withErrorCode("TASK_RUN_FAILED").withEndedAt(Instant.now())));
                return result(AgentLoopOutcome.FAILED, AgentSessionStatus.FAILED,
                        "TASK_RUN_FAILED", true);
            }
            seq = append(seq, sessionId, AgentEventType.RESOURCE_CREATED,
                    AgentEventVisibility.USER_AND_MODEL,
                    AgentEventFactory.payload()
                            .put("resourceType", "TASK_INSTANCE")
                            .put("resourceId", accepted.getTaskId())
                            .put("name", "task-instance"));
            seq = append(seq, sessionId, AgentEventType.RUN_COMPLETED,
                    AgentEventVisibility.USER_VISIBLE,
                    AgentEventFactory.payload().put("outcome", "COMPLETED").put("lastSeq", seq));
            persistence.updateSession(persistence.findSession(sessionId).orElseThrow()
                    .withStatus(AgentSessionStatus.READY).withActiveRunId(null));
            persistence.findRunByRequestId(sessionId, runRequestId).ifPresent(run ->
                    persistence.updateRun(run.withStatus(AgentRunStatus.COMPLETED)
                            .withEndedAt(Instant.now())));
            return result(AgentLoopOutcome.COMPLETED, AgentSessionStatus.READY, null, false);
        } finally {
            persistence.releaseLease(sessionId, workerId);
        }
    }

    private long append(long seq, String sessionId, AgentEventType type,
                        AgentEventVisibility visibility, com.fasterxml.jackson.databind.JsonNode payload) {
        java.util.List<com.consilens.agent.api.event.AgentEvent> persisted =
                persistence.appendEvents(sessionId, seq, java.util.List.of(
                        AgentEventFactory.create(type, visibility, sessionId, null, null, payload)));
        return persisted.get(persisted.size() - 1).getSeq() + 1;
    }

    private static AgentLoopResult result(AgentLoopOutcome outcome, AgentSessionStatus status,
                                          String errorCode, boolean retryable) {
        return AgentLoopResult.builder()
                .outcome(outcome)
                .sessionStatus(status)
                .runStatus(outcome == AgentLoopOutcome.COMPLETED
                        ? AgentRunStatus.COMPLETED : AgentRunStatus.FAILED)
                .errorCode(errorCode)
                .retryable(retryable)
                .build();
    }
}
