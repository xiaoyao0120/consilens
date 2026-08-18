package com.consilens.server.application.ai.plan;

import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.plan.AgentPlanAction;
import com.consilens.agent.api.plan.AgentPlanActionType;
import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentResourceRef;
import com.consilens.agent.api.store.AgentResourceType;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.agent.core.loop.AgentLoopOutcome;
import com.consilens.agent.core.loop.AgentLoopResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes an approved plan without any further model turn: events
 * RUN_STARTED -> PLAN_COMMIT_* -> RUN_COMPLETED. Partial failures keep the
 * completed resources and stop with a retryable RUN_FAILED.
 */
@Slf4j
public class ProvisioningPlanExecutor {

    private final AgentPersistence persistence;
    private final ProvisioningActionTransactionService transactionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProvisioningPlanExecutor(AgentPersistence persistence,
                                    ProvisioningActionTransactionService transactionService) {
        this.persistence = persistence;
        this.transactionService = transactionService;
    }

    public AgentLoopResult execute(String sessionId, String actorId, String planId, String workerId) {
        log.info("plan execution start: sessionId={} planId={} worker={}", sessionId, planId, workerId);
        Instant now = Instant.now();
        AgentProvisioningPlan plan = persistence.findPlan(planId).orElse(null);
        if (plan == null) {
            return result(AgentLoopOutcome.FAILED, AgentSessionStatus.FAILED, "PLAN_NOT_FOUND", false);
        }
        if (!persistence.tryAcquireLease(sessionId, actorId, workerId, now, now.plusSeconds(120))) {
            return result(AgentLoopOutcome.BUSY, AgentSessionStatus.RUNNING, "SESSION_BUSY", false);
        }
        try {
        if (plan.getStatus() == com.consilens.agent.api.plan.AgentPlanStatus.APPROVED
                && !persistence.claimApprovedPlan(planId, plan.getVersion(), workerId)) {
            return result(AgentLoopOutcome.FAILED, AgentSessionStatus.FAILED,
                    "PLAN_NOT_APPROVED", false);
        }
            AgentProvisioningPlan claimed = persistence.findPlan(planId).orElseThrow();
            long seq = persistence.nextSeq(sessionId);
            seq = append(seq, sessionId, AgentEventType.RUN_STARTED, AgentEventVisibility.AUDIT_ONLY,
                    AgentEventFactory.payload().put("workerId", workerId).put("resumeFromRunId", planId));
            seq = append(seq, sessionId, AgentEventType.PLAN_COMMIT_STARTED,
                    AgentEventVisibility.USER_VISIBLE,
                    AgentEventFactory.payload().put("planId", planId)
                            .put("actionCount", plan.getActions().size()));

            Map<String, String> datasourceIds = new LinkedHashMap<>();
            Map<String, String> datasourceTypes = new LinkedHashMap<>();
            long planVersion = claimed.getVersion();
            for (AgentPlanAction action : plan.getActions()) {
                if (action.getStatus() == com.consilens.agent.api.plan.AgentPlanActionStatus.SUCCEEDED) {
                    // 崩溃恢复：已成功 action 按 resourceId 跳过，不重复执行。
                    datasourceIds.put(draftKey(action), action.getResourceId());
                    if (action.getResourceType() == AgentResourceType.DATASOURCE) {
                        datasourceTypes.put(draftKey(action),
                                String.valueOf(action.getSafeArgs().get("type")));
                    }
                    continue;
                }
                seq = append(seq, sessionId, AgentEventType.PLAN_ACTION_STARTED,
                        AgentEventVisibility.USER_VISIBLE,
                        AgentEventFactory.payload()
                                .put("planId", planId)
                                .put("actionId", action.getActionId())
                                .put("index", action.getSequence())
                                .put("actionType", action.getActionType().name())
                                .put("label", action.getName()));
                try {
                    AgentResourceRef ref = executeAction(sessionId, actorId, planId, action,
                            datasourceIds, datasourceTypes, planVersion);
                    planVersion++;
                    datasourceIds.put(draftKey(action), ref.getResourceId());
                    if (ref.getResourceType() == AgentResourceType.DATASOURCE) {
                        datasourceTypes.put(draftKey(action),
                                String.valueOf(action.getSafeArgs().get("type")));
                    }
                    seq = append(seq, sessionId, AgentEventType.RESOURCE_CREATED,
                            AgentEventVisibility.USER_AND_MODEL,
                            AgentEventFactory.payload()
                                    .put("resourceType", ref.getResourceType().name())
                                    .put("resourceId", ref.getResourceId())
                                    .put("name", ref.getName()));
                    seq = append(seq, sessionId, AgentEventType.PLAN_ACTION_COMPLETED,
                            AgentEventVisibility.USER_AND_MODEL,
                            AgentEventFactory.payload()
                                    .put("planId", planId)
                                    .put("actionId", action.getActionId())
                                    .put("resourceRef", ref.getResourceId())
                                    .put("reused", action.getActionType() == AgentPlanActionType.REUSE_DATASOURCE));
                } catch (Exception e) {
                    log.warn("plan action {} failed: {}", action.getActionId(), e.getMessage());
                    seq = append(seq, sessionId, AgentEventType.PLAN_ACTION_FAILED,
                            AgentEventVisibility.USER_AND_MODEL,
                            AgentEventFactory.payload()
                                    .put("planId", planId)
                                    .put("actionId", action.getActionId())
                                    .put("errorCode", "PLAN_ACTION_FAILED")
                                    .put("retryable", true));
                    seq = append(seq, sessionId, AgentEventType.RUN_FAILED,
                            AgentEventVisibility.USER_AND_MODEL,
                            AgentEventFactory.payload()
                                    .put("errorCode", "PLAN_ACTION_FAILED")
                                    .put("retryable", true)
                                    .put("safeMessage", "计划第 " + action.getSequence()
                                            + " 步失败，已保留已完成资源"));
                    persistence.findRunByRequestId(sessionId, "plan_" + planId).ifPresent(run ->
                            persistence.updateRun(run.withStatus(AgentRunStatus.FAILED)
                                    .withErrorCode("PLAN_ACTION_FAILED")
                                    .withEndedAt(Instant.now())));
                    return result(AgentLoopOutcome.FAILED, AgentSessionStatus.FAILED,
                            "PLAN_ACTION_FAILED", true);
                }
            }

            seq = append(seq, sessionId, AgentEventType.PLAN_COMMIT_COMPLETED,
                    AgentEventVisibility.USER_AND_MODEL,
                    AgentEventFactory.payload().put("planId", planId)
                            .put("resourceRefs", mapper.valueToTree(datasourceIds)));
            log.info("plan execution completed: sessionId={} planId={} resources={}",
                    sessionId, planId, datasourceIds);
            seq = append(seq, sessionId, AgentEventType.RUN_COMPLETED,
                    AgentEventVisibility.USER_VISIBLE,
                    AgentEventFactory.payload().put("outcome", "COMPLETED").put("lastSeq", seq));
            updateSession(sessionId, AgentSessionStatus.READY,
                    com.consilens.agent.api.state.AgentWorkflowStage.RESOURCES_READY);
            persistence.findRunByRequestId(sessionId, "plan_" + planId).ifPresent(run ->
                    persistence.updateRun(run.withStatus(AgentRunStatus.COMPLETED)
                            .withEndedAt(Instant.now())));
            persistResourceRefs(sessionId, datasourceIds,
                    persistence.findPlan(planId).orElse(plan));
            return result(AgentLoopOutcome.COMPLETED, AgentSessionStatus.READY, null, false);
        } finally {
            persistence.releaseLease(sessionId, workerId);
        }
    }

    private AgentResourceRef executeAction(String sessionId, String actorId, String planId,
                                           AgentPlanAction action,
                                           Map<String, String> datasourceIds,
                                           Map<String, String> datasourceTypes,
                                           long planVersion) {
        switch (action.getActionType()) {
            case CREATE_DATASOURCE:
                return transactionService.completeDatasourceAction(planId, action.getActionId(),
                        sessionId, actorId, (String) action.getSafeArgs().get("draftId"), planVersion);
            case REUSE_DATASOURCE:
                AgentResourceRef ref = AgentResourceRef.builder()
                        .resourceType(AgentResourceType.DATASOURCE)
                        .resourceId((String) action.getSafeArgs().get("datasourceId"))
                        .name(action.getName())
                        .build();
                transactionService.completeReuseAction(planId, action.getActionId(), ref, planVersion);
                return ref;
            case CREATE_TASK_DEFINITION:
                return transactionService.completeTaskAction(planId, action.getActionId(),
                        action.getSafeArgs(),
                        datasourceIds.get("draft_source"),
                        datasourceIds.get("draft_target"),
                        datasourceTypes.get("draft_source"),
                        datasourceTypes.get("draft_target"),
                        planVersion);
            default:
                throw new IllegalStateException("unsupported plan action: " + action.getActionType());
        }
    }

    private static String draftKey(AgentPlanAction action) {
        Object side = action.getSafeArgs() == null ? null : action.getSafeArgs().get("side");
        return side == null ? "draft_" + action.getSequence() : "draft_" + side;
    }

    private void persistResourceRefs(String sessionId, Map<String, String> datasourceIds,
                                     AgentProvisioningPlan committedPlan) {
        AgentWorkingState state = persistence.latestSnapshot(sessionId)
                .map(com.consilens.agent.api.store.AgentSnapshotRecord::getWorkingState)
                .orElse(null);
        if (state == null) {
            return;
        }
        String taskId = committedPlan.getActions().stream()
                .filter(a -> a.getActionType() == AgentPlanActionType.CREATE_TASK_DEFINITION)
                .map(AgentPlanAction::getResourceId)
                .findFirst().orElse(null);
        AgentWorkingState updated = state.toBuilder()
                .source(state.getSource() == null ? null : state.getSource().toBuilder()
                        .datasourceId(datasourceIds.get("draft_source")).build())
                .target(state.getTarget() == null ? null : state.getTarget().toBuilder()
                        .datasourceId(datasourceIds.get("draft_target")).build())
                .task(state.getTask() == null ? null : state.getTask().toBuilder()
                        .definitionId(taskId).build())
                .stage(AgentWorkflowStage.RESOURCES_READY)
                .lastCompletedStep("PROVISIONING_COMMITTED")
                .build();
        com.consilens.agent.api.store.AgentSessionRecord session =
                persistence.findSession(sessionId).orElse(null);
        if (session != null) {
            persistence.saveSnapshot(sessionId, persistence.nextSeq(sessionId), updated,
                    "plan committed", 1, session.getVersion());
        }
    }

    private long append(long seq, String sessionId, AgentEventType type,
                        AgentEventVisibility visibility, JsonNode payload) {
        java.util.List<com.consilens.agent.api.event.AgentEvent> persisted =
                persistence.appendEvents(sessionId, seq, java.util.List.of(
                        AgentEventFactory.create(type, visibility, sessionId, null, null, payload)));
        return persisted.get(persisted.size() - 1).getSeq() + 1;
    }

    private void updateSession(String sessionId, AgentSessionStatus status,
                               com.consilens.agent.api.state.AgentWorkflowStage stage) {
        AgentSessionRecord session = persistence.findSession(sessionId).orElseThrow();
        persistence.updateSession(session.withStatus(status).withWorkflowStage(stage)
                .withActiveRunId(null));
    }

    private static AgentLoopResult result(AgentLoopOutcome outcome, AgentSessionStatus sessionStatus,
                                          String errorCode, boolean retryable) {
        return AgentLoopResult.builder()
                .outcome(outcome)
                .sessionStatus(sessionStatus)
                .runStatus(outcome == AgentLoopOutcome.COMPLETED
                        ? AgentRunStatus.COMPLETED : AgentRunStatus.FAILED)
                .errorCode(errorCode)
                .retryable(retryable)
                .build();
    }
}
