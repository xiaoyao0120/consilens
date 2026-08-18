package com.consilens.server.application.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.server.api.dto.ai.AgentSessionDto;
import com.consilens.server.api.dto.ai.SendAgentMessageResponse;
import com.consilens.server.application.ai.approval.AgentApprovalService;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application service for the conversation API: session lifecycle, message
 * acceptance (202 + durable run enqueue), approval decisions and retry.
 * HTTP threads never run the model loop here.
 */
public class AgentConversationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationApplicationService.class);

    private final AgentPersistence persistence;
    private final AgentRunEnqueueService runEnqueueService;
    private final AgentApprovalService approvalService;

    public AgentConversationApplicationService(AgentPersistence persistence,
                                               AgentRunEnqueueService runEnqueueService,
                                               AgentApprovalService approvalService) {
        this.persistence = persistence;
        this.runEnqueueService = runEnqueueService;
        this.approvalService = approvalService;
    }

    public AgentSessionDto createSession(String actorId, String requestId, String title) {
        AgentSessionRecord session = persistence.createSession(AgentSessionRecord.builder()
                .id("ai_" + UUID.randomUUID())
                .actorId(actorId)
                .requestId(requestId)
                .title(title)
                .status(AgentSessionStatus.READY)
                .workflowStage(AgentWorkflowStage.DISCOVERY)
                // 事件 seq 从 1 开始：0 保留给“未消费”游标，保证
                // 所有 seq > afterSeq 的查询（初始 afterSeq=0）不会漏掉首条消息。
                .nextSeq(1)
                .snapshotSeq(0)
                .version(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        log.info("agent session created: id={} actor={} title={}", session.getId(), actorId, title);
        return toDto(session);
    }

    public SendAgentMessageResponse sendMessage(String sessionId, String actorId,
                                                String requestId, String text) {
        AgentSessionRecord session = requireSession(sessionId, actorId);
        if (persistence.findRunByRequestId(sessionId, requestId).isPresent()) {
            log.info("agent message already processed: sessionId={} requestId={}", sessionId, requestId);
            return SendAgentMessageResponse.builder()
                    .status("ALREADY_PROCESSED")
                    .lastSeq(session.getNextSeq())
                    .build();
        }
        if (session.getStatus() == AgentSessionStatus.RUNNING) {
            // RUNNING 期间的消息作为 steering 排队，不开启第二个 run。
            long seq = persistence.nextSeq(sessionId);
            persistence.appendEvents(sessionId, seq, List.of(
                    AgentEventFactory.create(AgentEventType.STEERING_QUEUED,
                            AgentEventVisibility.USER_AND_MODEL, sessionId, null, null,
                            AgentEventFactory.payload()
                                    .put("requestId", requestId).put("text", text))));
            log.info("agent steering queued: sessionId={} requestId={} text={}",
                    sessionId, requestId, summarize(text));
            return SendAgentMessageResponse.builder()
                    .status("STEERING_QUEUED")
                    .lastSeq(Math.max(0, persistence.nextSeq(sessionId) - 1))
                    .build();
        }
        long seq = persistence.nextSeq(sessionId);
        persistence.appendEvents(sessionId, seq, List.of(
                AgentEventFactory.create(AgentEventType.USER_MESSAGE,
                        AgentEventVisibility.USER_AND_MODEL, sessionId, null, null,
                        AgentEventFactory.payload().put("requestId", requestId).put("text", text))));
        String runId = "run_" + UUID.randomUUID();
        persistence.createRun(AgentRunRecord.builder()
                .runId(runId)
                .sessionId(sessionId)
                .requestId(requestId)
                .status(AgentRunStatus.QUEUED)
                .startedAt(Instant.now())
                .build());
        log.info("agent message accepted: sessionId={} requestId={} text={} lastSeq={}",
                sessionId, requestId, summarize(text), persistence.nextSeq(sessionId));
        return SendAgentMessageResponse.builder()
                .runId(runId)
                .status("ACCEPTED")
                .lastSeq(Math.max(0, persistence.nextSeq(sessionId) - 1))
                .build();
    }

    public SendAgentMessageResponse decideApproval(String sessionId, String actorId,
                                                   String approvalId, String requestId,
                                                   AgentApprovalDecision decision,
                                                   String actionDigest, Long version) {
        AgentSessionRecord session = requireSession(sessionId, actorId);
        AgentApprovalRecord approval = persistence.findApproval(approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("approval not found: " + approvalId));
        if (!approval.getSessionId().equals(sessionId)) {
            throw new IllegalStateException("APPROVAL_NOT_FOUND");
        }
        AgentApprovalService.DecideOutcome outcome = approvalService.decide(
                approvalId, actorId, version == null ? approval.getVersion() : version,
                decision, actionDigest, Instant.now());
        if (!outcome.succeeded()) {
            throw new IllegalStateException(outcome.errorCode());
        }
        long seq = persistence.nextSeq(sessionId);
        persistence.appendEvents(sessionId, seq, List.of(
                AgentEventFactory.create(AgentEventType.APPROVAL_DECIDED,
                        AgentEventVisibility.USER_AND_MODEL, sessionId, null, null,
                        AgentEventFactory.payload()
                                .put("approvalId", approvalId)
                                .put("decision", decision.name())
                                .put("actorId", actorId))));
        if (decision == AgentApprovalDecision.APPROVE) {
            log.info("agent approval approved: sessionId={} approvalId={}",
                    sessionId, approvalId);
            String planId = approval.getProposedRunId();
            if (planId != null && planId.startsWith("run:")) {
                runEnqueueService.enqueueResume(sessionId, "run_" + approvalId,
                        "run:" + approvalId);
            } else if (planId != null) {
                // 先校验计划仍可审批，再决定；approvePlan 失败视为并发竞争，仍入队（digest 已在 pre-check 校验）。
                boolean planPending = persistence.findPlan(planId)
                        .map(p -> p.getStatus()
                                == com.consilens.agent.api.plan.AgentPlanStatus.PREPARED)
                        .orElse(false);
                if (!planPending) {
                    throw new IllegalStateException("PLAN_DIGEST_MISMATCH");
                }
                boolean approved = persistence.findPlan(planId)
                        .map(plan -> persistence.approvePlan(planId, plan.getVersion()))
                        .orElse(false);
                if (!approved) {
                    // 并发下另一审批已推进计划：digest 相同，仍可执行。
                }
                runEnqueueService.enqueueResume(sessionId, "plan_" + planId, "plan:" + planId);
            }
        } else {
            log.info("agent approval denied: sessionId={} approvalId={}",
                    sessionId, approvalId);
            // DENY：会话回到 READY，等待用户调整后重新发起。
            persistence.updateSession(persistence.findSession(sessionId).orElseThrow()
                    .withStatus(AgentSessionStatus.READY).withActiveRunId(null));
        }
        return SendAgentMessageResponse.builder()
                .status(outcome.status() == null ? "DECIDED" : outcome.status().name())
                .lastSeq(persistence.nextSeq(sessionId))
                .build();
    }

    public void cancel(String sessionId, String actorId) {
        AgentSessionRecord session = requireSession(sessionId, actorId);
        if (session.getActiveRunId() != null) {
            persistence.findRun(session.getActiveRunId()).ifPresent(run ->
                    persistence.updateRun(run.withStatus(AgentRunStatus.CANCELLED)
                            .withEndedAt(Instant.now())));
        }
        persistence.updateSession(session.withStatus(AgentSessionStatus.CANCELLED)
                .withActiveRunId(null));
    }

    public void retry(String sessionId, String actorId, String requestId) {
        requireSession(sessionId, actorId);
        persistence.findRunByRequestId(sessionId, requestId)
                .filter(run -> run.getStatus() == AgentRunStatus.FAILED)
                .ifPresent(run -> persistence.updateRun(run.withStatus(AgentRunStatus.QUEUED)));
    }

    public AgentSessionDto getSession(String sessionId, String actorId) {
        return toDto(requireSession(sessionId, actorId));
    }

    public List<AgentEvent> events(String sessionId, String actorId, long afterSeq) {
        requireSession(sessionId, actorId);
        return persistence.listEventsAfter(sessionId, afterSeq, 500);
    }

    public void deleteSession(String sessionId, String actorId) {
        requireSession(sessionId, actorId);
        persistence.deleteSession(sessionId);
        log.info("agent session deleted: id={} actor={}", sessionId, actorId);
    }

    private AgentSessionRecord requireSession(String sessionId, String actorId) {
        AgentSessionRecord session = persistence.findSession(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("session not found: " + sessionId));
        if (!session.getActorId().equals(actorId)) {
            throw new ResourceNotFoundException("session not found: " + sessionId);
        }
        return session;
    }

    private static String summarize(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "…";
    }

    private AgentSessionDto toDto(AgentSessionRecord session) {
        AgentWorkingState workingState = persistence.latestSnapshot(session.getId())
                .map(s -> s.getWorkingState())
                .orElse(AgentWorkingState.builder()
                        .objectiveId("obj_" + session.getId())
                        .objective(session.getObjective() == null ? "" : session.getObjective())
                        .stage(AgentWorkflowStage.DISCOVERY)
                        .build());
        return AgentSessionDto.builder()
                .id(session.getId())
                .status(session.getStatus().name())
                // lastSeq 语义为“已落库的最大事件 seq”：空会话为 0，有事件时为 nextSeq-1。
                // 前端以 lastSeq 作为 SSE/补齐游标（afterSeq=lastSeq，查询 seq > afterSeq）。
                .lastSeq(Math.max(0, session.getNextSeq() - 1))
                .workingState(workingState)
                .build();
    }
}
