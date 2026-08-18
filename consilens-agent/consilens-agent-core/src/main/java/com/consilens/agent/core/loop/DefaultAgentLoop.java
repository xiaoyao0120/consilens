package com.consilens.agent.core.loop;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.model.AgentModelClient;
import com.consilens.agent.api.model.AgentModelEventListener;
import com.consilens.agent.api.model.AgentModelFinishReason;
import com.consilens.agent.api.model.AgentModelRequest;
import com.consilens.agent.api.model.AgentModelResponse;
import com.consilens.agent.api.model.AgentModelToolCall;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.api.store.AgentSnapshotRecord;
import com.consilens.agent.api.store.AgentToolCallRecord;
import com.consilens.agent.api.tool.AgentToolCallStatus;
import com.consilens.agent.api.tool.AgentToolContext;
import com.consilens.agent.api.tool.AgentToolOutcome;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.agent.core.security.SensitiveValueGuard;
import com.consilens.agent.core.state.AgentStateMachine;
import com.consilens.agent.core.tool.AgentToolBatchExecutor;
import com.consilens.agent.core.tool.AgentToolExecutionResult;
import com.consilens.agent.core.tool.AgentToolPreflight;
import com.consilens.agent.core.tool.AgentToolRegistry;
import com.consilens.agent.core.tool.DefaultAgentToolContext;
import com.consilens.agent.core.tool.PreflightResult;
import com.consilens.agent.core.tool.PreflightStatus;
import com.consilens.agent.core.tool.PreparedToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Durable agent loop (section 5 and 20): lease-protected, event-sourced,
 * budget-bounded LLM &lt;-&gt; tool loop. HTTP request threads never run this;
 * workers claim runs through persistence. State only changes through typed
 * reducers validated by the invariant validator.
 */
public final class DefaultAgentLoop implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentLoop.class);
    private static final int SNAPSHOT_EVENT_INTERVAL = 50;

    private final AgentPersistence persistence;
    private final AgentModelClient modelClient;
    private final AgentToolRegistry registry;
    private final AgentContextAssembler contextAssembler;
    private final AgentToolPreflight preflight;
    private final AgentToolBatchExecutor batchExecutor;
    private final AgentStopEvaluator stopEvaluator;
    private final AgentRunConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultAgentLoop(AgentPersistence persistence,
                            AgentModelClient modelClient,
                            AgentToolRegistry registry,
                            AgentContextAssembler contextAssembler,
                            AgentRunConfig config) {
        this.persistence = persistence;
        this.modelClient = modelClient;
        this.registry = registry;
        this.contextAssembler = contextAssembler;
        this.preflight = new AgentToolPreflight(registry, mapper);
        this.batchExecutor = new AgentToolBatchExecutor(config.isParallelReadsEnabled());
        this.stopEvaluator = new AgentStopEvaluator();
        this.config = config;
    }

    @Override
    public AgentLoopResult run(AgentLoopRequest request, AgentCancellationToken cancellationToken) {
        return execute(request, cancellationToken, false);
    }

    @Override
    public AgentLoopResult continueRun(AgentLoopRequest request, AgentCancellationToken cancellationToken) {
        return execute(request, cancellationToken, true);
    }

    private AgentLoopResult execute(AgentLoopRequest request,
                                    AgentCancellationToken cancellationToken,
                                    boolean resume) {
        AgentSessionRecord session = persistence.findSession(request.getSessionId()).orElse(null);
        if (session == null || !session.getActorId().equals(request.getActorId())) {
            return result(AgentLoopOutcome.SESSION_NOT_FOUND, null, null, 0, "SESSION_NOT_FOUND", false);
        }
        if (resume && !isResumable(session.getStatus())) {
            return result(AgentLoopOutcome.BUSY, session.getStatus(), null, session.getNextSeq(),
                    "SESSION_BUSY", false);
        }

        Optional<AgentRunRecord> existingRun = persistence.findRunByRequestId(session.getId(), request.getRequestId());
        if (existingRun.isPresent()) {
            AgentRunRecord run = existingRun.get();
            if (run.getStatus() == AgentRunStatus.COMPLETED) {
                log.info("agent run already completed: sessionId={} runId={} requestId={}",
                        request.getSessionId(), run.getRunId(), request.getRequestId());
                return result(AgentLoopOutcome.COMPLETED, session.getStatus(), run.getStatus(),
                        session.getNextSeq(), null, false);
            }
            if ((run.getStatus() == AgentRunStatus.RUNNING || run.getStatus() == AgentRunStatus.QUEUED)
                    && !request.isResumeExistingRun()) {
                log.warn("agent run busy: sessionId={} requestId={} runStatus={}",
                        request.getSessionId(), request.getRequestId(), run.getStatus());
                return result(AgentLoopOutcome.BUSY, session.getStatus(), run.getStatus(),
                        session.getNextSeq(), "SESSION_BUSY", false);
            }
        }

        String workerId = request.getWorkerId() == null ? "local-worker" : request.getWorkerId();
        Instant now = Instant.now();
        Duration lease = config.getRunTimeout().plusSeconds(30);
        if (!persistence.tryAcquireLease(session.getId(), session.getActorId(), workerId, now, now.plus(lease))) {
            log.warn("agent lease busy: sessionId={} worker={}", session.getId(), workerId);
            return result(AgentLoopOutcome.BUSY, session.getStatus(), null, session.getNextSeq(),
                    "SESSION_BUSY", false);
        }
        try {
            log.info("agent run start: sessionId={} runId={} requestId={} resume={} worker={}",
                    request.getSessionId(), request.getRunId(), request.getRequestId(),
                    resume, workerId);
            return executeRun(request, cancellationToken, workerId);
        } finally {
            persistence.releaseLease(session.getId(), workerId);
        }
    }

    private AgentLoopResult executeRun(AgentLoopRequest request,
                                       AgentCancellationToken cancellationToken,
                                       String workerId) {
        String sessionId = request.getSessionId();
        String runId = request.getRunId() == null ? UUID.randomUUID().toString() : request.getRunId();
        String turnId = null;
        long nextSeq = persistence.nextSeq(sessionId);
        int turnCount = 0;
        int toolCallCount = 0;

        setSessionStatus(sessionId, AgentSessionStatus.RUNNING);
        upsertRun(sessionId, request, runId, AgentRunStatus.RUNNING);

        nextSeq = append(nextSeq, sessionId, runId, null, AgentEventType.RUN_STARTED,
                AgentEventVisibility.AUDIT_ONLY,
                AgentEventFactory.payload().put("workerId", workerId)
                        .put("resumeFromRunId", request.getResumeFromRunId() == null
                                ? "" : request.getResumeFromRunId()));

        AgentWorkingState state = loadInitialState(sessionId, request);
        // 模型上下文由事件构建（Events are the only message source）。
        // 快照只用于恢复 working state，不能作为事件加载起点，否则 run 结束时
        // 推进的 snapshotSeq 会把本次用户消息从下一轮上下文里裁掉。
        // 从会话开头加载，由 AgentContextAssembler 的 maxRecentEvents 截断。
        List<AgentEvent> events = persistence.listEventsAfter(sessionId, -1, 200);

        for (int turn = 1; turn <= config.getMaxTurns(); turn++) {
            turnCount = turn;
            long turnStarted = System.currentTimeMillis();
            if (cancellationToken.isCancelled()) {
        return finish(sessionId, runId, state, AgentLoopOutcome.CANCELLED,
                        AgentSessionStatus.CANCELLED, "RUN_CANCELLED", null, false, turnId, nextSeq);
            }
            if (request.getDeadline() != null && Instant.now().isAfter(request.getDeadline())) {
                return finish(sessionId, runId, state, AgentLoopOutcome.TIMEOUT,
                        AgentSessionStatus.FAILED, "RUN_TIMEOUT", "run deadline exceeded", true,
                        turnId, nextSeq);
            }
            renewLease(sessionId, workerId);

            turnId = "turn_" + UUID.randomUUID();
            log.info("agent turn {}: sessionId={} runId={} stage={}",
                    turn, sessionId, runId, state.getStage());
            nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TURN_STARTED,
                    AgentEventVisibility.AUDIT_ONLY,
                    AgentEventFactory.payload().put("turnIndex", turn));

            AgentModelRequest modelRequest = contextAssembler.build(state, events, registry);
            AgentModelResponse response = modelClient.complete(modelRequest, new NoopListener(),
                    cancellationToken);
            log.info("agent turn {} model done: finishReason={} textChars={} toolCalls={} durationMs={}",
                    turn, response.getFinishReason(),
                    response.getText() == null ? 0 : response.getText().length(),
                    response.getToolCalls() == null ? 0 : response.getToolCalls().size(),
                    response.getDurationMillis());

            nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.MODEL_USAGE,
                    AgentEventVisibility.AUDIT_ONLY, modelUsagePayload(modelRequest, response));

            if (response.isFailed()) {
                log.warn("agent run failed at turn {}: sessionId={} errorCode={} retryable={} message={}",
                        turn, sessionId, response.getErrorCode(), response.isRetryable(),
                        response.getSafeMessage());
                if ("MODEL_CANCELLED".equals(response.getErrorCode())) {
                    return finish(sessionId, runId, state, AgentLoopOutcome.CANCELLED,
                            AgentSessionStatus.CANCELLED, "RUN_CANCELLED", null, false, turnId, nextSeq);
                }
                return finish(sessionId, runId, state, AgentLoopOutcome.FAILED,
                        AgentSessionStatus.FAILED, response.getErrorCode(), response.getSafeMessage(),
                        response.isRetryable(), turnId, nextSeq);
            }

            if (response.getText() != null && !response.getText().isBlank()) {
                nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.ASSISTANT_MESSAGE,
                        AgentEventVisibility.USER_AND_MODEL,
                        AgentEventFactory.payload()
                                .put("text", response.getText())
                                .put("reasoningContent", response.getReasoningContent() == null
                                        ? "" : response.getReasoningContent()));
            }

            if (response.getFinishReason() == AgentModelFinishReason.LENGTH
                    && !response.getToolCalls().isEmpty()) {
                nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_BATCH_REJECTED,
                        AgentEventVisibility.USER_AND_MODEL,
                        AgentEventFactory.payload().put("errorCode", "TRUNCATED_ARGUMENTS"));
                continue;
            }

            if (response.getToolCalls().isEmpty()) {
                AgentStopDecision stop = stopEvaluator.evaluate(response.getText(), state);
                log.info("agent turn {} no tool calls, stop decision: reason={}",
                        turn, stop.getReason());
                nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TURN_COMPLETED,
                        AgentEventVisibility.AUDIT_ONLY,
                        AgentEventFactory.payload().put("turnIndex", turn));
                if (stop.getReason() == AgentStopReason.WAITING_INPUT) {
                    return suspend(sessionId, runId, state, AgentSessionStatus.WAITING_INPUT,
                            "missing input", turnId, nextSeq);
                }
                return finish(sessionId, runId, state, AgentLoopOutcome.COMPLETED,
                        AgentSessionStatus.READY, null, null, false, turnId, nextSeq);
            }

            // Tool batch: preflight + sequential execution in model source order.
            AgentWorkingState batchState = state;
            boolean suspended = false;
            AgentLoopResult suspension = null;
            for (AgentModelToolCall modelCall : response.getToolCalls()) {
                if (toolCallCount >= config.getMaxToolCalls()) {
                    log.warn("agent tool budget exhausted: sessionId={} toolCalls={} max={}",
                            sessionId, toolCallCount, config.getMaxToolCalls());
                    return finish(sessionId, runId, state, AgentLoopOutcome.BUDGET_EXHAUSTED,
                            AgentSessionStatus.FAILED, "AGENT_BUDGET_EXHAUSTED",
                            "tool call budget exhausted", true, turnId, nextSeq);
                }
                toolCallCount++;
                long toolStarted = System.currentTimeMillis();
                PreflightResult prepared = preflight.prepare(modelCall, batchState, sessionId,
                        Set.of(), toolCallCount);
                if (prepared.getStatus() == PreflightStatus.BLOCKED) {
                    log.warn("agent tool blocked: sessionId={} callId={} tool={} errorCode={}",
                            sessionId, modelCall.getId(), modelCall.getName(),
                            prepared.getErrorCode());
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_PROPOSED,
                            AgentEventVisibility.MODEL_VISIBLE,
                            AgentEventFactory.payload()
                                    .put("callId", modelCall.getId())
                                    .put("toolName", modelCall.getName())
                                    .put("redactedArgsDigest", "")
                                    .put("reasoningContent", response.getReasoningContent() == null
                                            ? "" : response.getReasoningContent())
                                    .set("arguments", SensitiveValueGuard.redact(parseArgs(modelCall))));
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_BLOCKED,
                            AgentEventVisibility.USER_AND_MODEL, blockedPayload(modelCall, prepared));
                    saveToolCall(sessionId, runId, turnId, modelCall, prepared, AgentToolCallStatus.BLOCKED,
                            null, null, prepared.getErrorCode(), null);
                    continue;
                }

                if (prepared.getPrepared().getIdempotencyKey() != null
                        && persistence.findSucceededByIdempotencyKey(sessionId,
                        prepared.getPrepared().getIdempotencyKey()).isPresent()) {
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_PROPOSED,
                            AgentEventVisibility.MODEL_VISIBLE,
                            AgentEventFactory.payload()
                                    .put("callId", modelCall.getId())
                                    .put("toolName", modelCall.getName())
                                    .put("redactedArgsDigest", prepared.getPrepared().getArgsDigest())
                                    .put("reasoningContent", response.getReasoningContent() == null
                                            ? "" : response.getReasoningContent())
                                    .set("arguments", SensitiveValueGuard.redact(parseArgs(modelCall))));
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_COMPLETED,
                            AgentEventVisibility.USER_AND_MODEL,
                            AgentEventFactory.payload()
                                    .put("callId", modelCall.getId())
                                    .put("status", "SUCCEEDED")
                                    .put("reused", true)
                                    .put("safeSummary", "已复用此前成功结果（幂等）"));
                    continue;
                }

                nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_PROPOSED,
                        AgentEventVisibility.MODEL_VISIBLE,
                        AgentEventFactory.payload()
                                .put("callId", modelCall.getId())
                                .put("toolName", modelCall.getName())
                                .put("redactedArgsDigest", prepared.getPrepared().getArgsDigest())
                                .put("reasoningContent", response.getReasoningContent() == null
                                        ? "" : response.getReasoningContent())
                                .set("arguments", SensitiveValueGuard.redact(parseArgs(modelCall))));

                if (prepared.getStatus() == PreflightStatus.REUSED) {
                    log.info("agent tool reused: sessionId={} callId={} tool={}",
                            sessionId, modelCall.getId(), modelCall.getName());
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_COMPLETED,
                            AgentEventVisibility.USER_AND_MODEL,
                            AgentEventFactory.payload()
                                    .put("callId", modelCall.getId())
                                    .put("status", "SUCCEEDED")
                                    .put("reused", true)
                                    .put("safeSummary", "reused previous result"));
                    continue;
                }

                nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_STARTED,
                        AgentEventVisibility.USER_VISIBLE,
                        AgentEventFactory.payload()
                                .put("callId", modelCall.getId())
                                .put("toolName", modelCall.getName())
                                .put("label", modelCall.getName()));
                saveToolCall(sessionId, runId, turnId, modelCall, prepared, AgentToolCallStatus.RUNNING,
                        null, null, null, null);

                AgentToolExecutionResult execution;
                try {
                    DefaultAgentToolContext toolContext = new DefaultAgentToolContext(
                            request.getActorId(), sessionId, runId, turnId, request.getObjectiveId(),
                            batchState, NoopSecretResolver.INSTANCE, Set.of());
                    execution = executeOne(prepared.getPrepared(), batchState, toolContext,
                            cancellationToken);
                } catch (RuntimeException e) {
                    // reducer/validator 异常不能卡住 run：结构化失败并停止。
                    return finish(sessionId, runId, state, AgentLoopOutcome.FAILED,
                            AgentSessionStatus.FAILED, "TOOL_EXECUTION_FAILED",
                            "工具执行内部错误", true, turnId, nextSeq);
                }

                AgentToolOutcome<?> outcome = execution.getOutcome();
                log.info("agent tool done: sessionId={} callId={} tool={} success={} errorCode={} durationMs={}",
                        sessionId, modelCall.getId(), modelCall.getName(), outcome.isSuccess(),
                        outcome.getErrorCode(), System.currentTimeMillis() - toolStarted);
                String resultJson = "";
                if (outcome.getStructuredData() != null) {
                    try {
                        resultJson = mapper.writeValueAsString(outcome.getStructuredData());
                    } catch (Exception ignored) {
                        // 结构化结果序列化失败时退化为 safeSummary
                    }
                }
                nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TOOL_COMPLETED,
                        AgentEventVisibility.USER_AND_MODEL,
                        AgentEventFactory.payload()
                                .put("callId", modelCall.getId())
                                .put("status", outcome.isSuccess() ? "SUCCEEDED" : "FAILED")
                                .put("reused", false)
                                .put("resultJson", resultJson)
                                .put("safeSummary", outcome.getContent() == null ? "" : outcome.getContent()));
                completeToolCall(modelCall.getId(),
                        outcome.isSuccess() ? AgentToolCallStatus.SUCCEEDED : AgentToolCallStatus.FAILED,
                        outcome.getErrorCode(), outcome.isRetryable(), outcome.getContent());

                if (execution.isStateChanged()) {
                    batchState = execution.getNewState();
                    state = batchState;
                }
                if (outcome.isQuestionRequired()) {
                    ObjectNode questionPayload = AgentEventFactory.payload();
                    questionPayload.put("questionId", "q_" + UUID.randomUUID());
                    questionPayload.put("question", outcome.getQuestion() == null ? "" : outcome.getQuestion());
                    com.fasterxml.jackson.databind.node.ArrayNode slots =
                            questionPayload.putArray("missingSlots");
                    if (outcome.getMissingSlots() != null) {
                        outcome.getMissingSlots().forEach(slots::add);
                    }
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.QUESTION_REQUIRED,
                            AgentEventVisibility.USER_AND_MODEL, questionPayload);
                    suspension = suspend(sessionId, runId, state, AgentSessionStatus.WAITING_INPUT,
                            outcome.getQuestion(), turnId, nextSeq);
                    suspended = true;
                    break;
                }
                if (outcome.isSecretInputRequired()) {
                    ObjectNode secretPayload = AgentEventFactory.payload();
                    secretPayload.put("secretRequestId", outcome.getSecretRequestId())
                            .put("draftId", outcome.getDraftId() == null ? "" : outcome.getDraftId())
                            .put("expiresAt", Instant.now().plusSeconds(600).toString());
                    com.fasterxml.jackson.databind.node.ArrayNode secretFields =
                            secretPayload.putArray("fields");
                    if (outcome.getMissingSlots() != null) {
                        outcome.getMissingSlots().forEach(secretFields::add);
                    }
                    if (outcome.getSecretExpiresAt() != null) {
                        secretPayload.put("expiresAt", outcome.getSecretExpiresAt().toString());
                    }
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.SECRET_INPUT_REQUIRED,
                            AgentEventVisibility.USER_VISIBLE, secretPayload);
                    suspension = suspend(sessionId, runId, state, AgentSessionStatus.WAITING_SECRET,
                            "secret input required", turnId, nextSeq);
                    suspended = true;
                    break;
                }
                if (outcome.isApprovalRequired()) {
                    ObjectNode approvalPayload = AgentEventFactory.payload();
                    approvalPayload.put("approvalId", outcome.getApprovalId() == null ? "" : outcome.getApprovalId())
                            .put("actionDigest", outcome.getActionDigest() == null ? "" : outcome.getActionDigest())
                            .put("summary", outcome.getApprovalSummary() == null ? "" : outcome.getApprovalSummary())
                            .put("expiresAt", Instant.now().plusSeconds(600).toString());
                    nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.APPROVAL_REQUIRED,
                            AgentEventVisibility.USER_VISIBLE, approvalPayload);
                    suspension = suspend(sessionId, runId, state, AgentSessionStatus.WAITING_APPROVAL,
                            outcome.getApprovalSummary(), turnId, nextSeq);
                    suspended = true;
                    break;
                }
            }
            if (suspended) {
                return suspension;
            }
            state = batchState;

            nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.TURN_COMPLETED,
                    AgentEventVisibility.AUDIT_ONLY,
                    AgentEventFactory.payload().put("turnIndex", turn));
            events = persistence.listEventsAfter(sessionId, -1, 200);
            if (turnCount % SNAPSHOT_EVENT_INTERVAL == 0) {
                snapshot(sessionId, state, "turn checkpoint", nextSeq);
            }
        }

        return finish(sessionId, runId, state, AgentLoopOutcome.BUDGET_EXHAUSTED,
                AgentSessionStatus.FAILED, "AGENT_BUDGET_EXHAUSTED", "turn budget exhausted", true,
                turnId, nextSeq);
    }

    private AgentToolExecutionResult executeOne(PreparedToolCall prepared,
                                                AgentWorkingState state,
                                                AgentToolContext context,
                                                AgentCancellationToken cancellationToken) {
        List<PreparedToolCall> batch = List.of(prepared);
        List<AgentToolExecutionResult> results = batchExecutor.execute(batch, state, context, cancellationToken);
        return results.get(0);
    }

    private AgentLoopResult suspend(String sessionId,
                                    String runId,
                                    AgentWorkingState state,
                                    AgentSessionStatus sessionStatus,
                                    String reason,
                                    String turnId,
                                    long nextSeq) {
        log.info("agent run suspended: sessionId={} runId={} status={} reason={}",
                sessionId, runId, sessionStatus, reason);
        nextSeq = append(nextSeq, sessionId, runId, turnId, AgentEventType.RUN_SUSPENDED,
                AgentEventVisibility.USER_AND_MODEL,
                AgentEventFactory.payload()
                        .put("reason", sessionStatus.name())
                        .put("message", reason == null ? "" : reason));
        AgentSessionRecord session = persistence.findSession(sessionId).orElseThrow();
        boolean updated = persistence.updateSession(session.withStatus(sessionStatus)
                .withActiveRunId(runId));
        if (!updated) {
            AgentSessionRecord fresh = persistence.findSession(sessionId).orElseThrow();
            persistence.updateSession(fresh.withStatus(sessionStatus).withActiveRunId(runId));
        }
        AgentRunRecord run = persistence.findRun(runId).orElseThrow();
        persistence.updateRun(run.withStatus(AgentRunStatus.WAITING));
        snapshot(sessionId, state, "suspended", nextSeq);
        return result(AgentLoopOutcome.SUSPENDED, sessionStatus, AgentRunStatus.WAITING, nextSeq,
                null, false);
    }

    private AgentLoopResult finish(String sessionId,
                                   String runId,
                                   AgentWorkingState state,
                                   AgentLoopOutcome outcome,
                                   AgentSessionStatus sessionStatus,
                                   String errorCode,
                                   String safeMessage,
                                   boolean retryable,
                                   String turnId,
                                   long nextSeq) {
        log.info("agent run finished: sessionId={} runId={} outcome={} sessionStatus={} "
                        + "errorCode={} retryable={}",
                sessionId, runId, outcome, sessionStatus, errorCode, retryable);
        AgentEventType terminalType;
        AgentEventVisibility terminalVisibility;
        switch (outcome) {
            case CANCELLED:
                terminalType = AgentEventType.RUN_CANCELLED;
                terminalVisibility = AgentEventVisibility.USER_AND_MODEL;
                break;
            case FAILED:
            case BUDGET_EXHAUSTED:
            case TIMEOUT:
                terminalType = AgentEventType.RUN_FAILED;
                terminalVisibility = AgentEventVisibility.USER_AND_MODEL;
                break;
            default:
                terminalType = AgentEventType.RUN_COMPLETED;
                terminalVisibility = AgentEventVisibility.USER_VISIBLE;
                break;
        }
        ObjectNode payload = AgentEventFactory.payload();
        if (outcome == AgentLoopOutcome.COMPLETED) {
            payload.put("outcome", "COMPLETED").put("lastSeq", nextSeq);
        } else if (errorCode != null) {
            payload.put("errorCode", errorCode)
                    .put("retryable", retryable)
                    .put("safeMessage", safeMessage == null ? "" : safeMessage);
        } else {
            payload.put("outcome", outcome.name());
        }
        nextSeq = append(nextSeq, sessionId, runId, turnId, terminalType, terminalVisibility, payload);

        AgentSessionRecord session = persistence.findSession(sessionId).orElseThrow();
        AgentRunRecord run = persistence.findRun(runId).orElseThrow();
        AgentRunStatus runStatus = outcome == AgentLoopOutcome.COMPLETED
                ? AgentRunStatus.COMPLETED
                : outcome == AgentLoopOutcome.CANCELLED ? AgentRunStatus.CANCELLED
                : AgentRunStatus.FAILED;
        persistence.updateSession(session.withStatus(sessionStatus).withActiveRunId(null));
        persistence.updateRun(run.withStatus(runStatus)
                .withErrorCode(errorCode)
                .withEndedAt(Instant.now()));
        snapshot(sessionId, state, "run end", nextSeq);
        return result(outcome, sessionStatus, runStatus, nextSeq, errorCode, retryable);
    }

    private void snapshot(String sessionId, AgentWorkingState state, String summary, long seq) {
        AgentSessionRecord session = persistence.findSession(sessionId).orElseThrow();
        persistence.saveSnapshot(sessionId, seq, state, summary, 1, session.getVersion());
    }

    private long append(long expectedNextSeq, String sessionId, String runId, String turnId,
                        AgentEventType type, AgentEventVisibility visibility, JsonNode payload) {
        AgentEvent event = AgentEventFactory.create(type, visibility, sessionId, runId, turnId, payload);
        List<AgentEvent> persisted = persistence.appendEvents(sessionId, expectedNextSeq, List.of(event));
        if (persisted == null) {
            throw new IllegalStateException("event seq conflict for session " + sessionId);
        }
        return persisted.get(persisted.size() - 1).getSeq() + 1;
    }

    private AgentWorkingState loadInitialState(String sessionId, AgentLoopRequest request) {
        Optional<AgentSnapshotRecord> latest = persistence.latestSnapshot(sessionId);
        if (latest.isPresent()) {
            return latest.get().getWorkingState();
        }
        return AgentWorkingState.builder()
                .objectiveId(request.getObjectiveId() == null ? "obj_" + UUID.randomUUID() : request.getObjectiveId())
                .objective(request.getObjective() == null ? "" : request.getObjective())
                .stage(AgentWorkflowStage.DISCOVERY)
                .build();
    }

    private AgentRunRecord upsertRun(String sessionId, AgentLoopRequest request,
                                     String runId, AgentRunStatus status) {
        Optional<AgentRunRecord> existing = persistence.findRunByRequestId(sessionId, request.getRequestId());
        AgentRunRecord run = existing.orElseGet(() -> persistence.createRun(AgentRunRecord.builder()
                .runId(runId)
                .sessionId(sessionId)
                .requestId(request.getRequestId())
                .resumeFromRunId(request.getResumeFromRunId())
                .status(AgentRunStatus.QUEUED)
                .startedAt(Instant.now())
                .build()));
        if (existing.isPresent()) {
            run = run.withRunId(runId).withStatus(status);
        } else {
            run = run.withStatus(status);
        }
        persistence.updateRun(run);
        return run;
    }

    private void setSessionStatus(String sessionId, AgentSessionStatus status) {
        AgentSessionRecord session = persistence.findSession(sessionId).orElseThrow();
        AgentSessionStatus current = session.getStatus();
        if (current != status && !AgentStateMachine.canTransition(current, status)) {
            throw new IllegalStateException("illegal session transition " + current + " -> " + status);
        }
        persistence.updateSession(session.withStatus(status));
    }

    private void renewLease(String sessionId, String workerId) {
        AgentSessionRecord session = persistence.findSession(sessionId).orElseThrow();
        persistence.renewLease(sessionId, workerId, session.getVersion(),
                Instant.now().plus(config.getRunTimeout().plusSeconds(30)));
    }

    private void saveToolCall(String sessionId, String runId, String turnId, AgentModelToolCall modelCall,
                              PreflightResult prepared, AgentToolCallStatus status, String errorCode,
                              Boolean retryable, String resultSummary, String idempotencyKey) {
        Optional<AgentToolCallRecord> existing = persistence.findSucceededByIdempotencyKey(sessionId, idempotencyKey);
        if (existing.isPresent()) {
            return;
        }
        AgentToolCallRecord record = AgentToolCallRecord.builder()
                .callId(modelCall.getId())
                .sessionId(sessionId)
                .runId(runId)
                .turnId(turnId)
                .sourceOrder(0)
                .toolName(modelCall.getName())
                .status(status)
                .riskLevel(prepared.getPrepared() == null ? null : prepared.getPrepared().getTool()
                        .descriptor().getRiskLevel())
                .redactedArgs(SensitiveValueGuard.redact(parseArgs(modelCall)).toString())
                .argsDigest(prepared.getPrepared() == null ? null : prepared.getPrepared().getArgsDigest())
                .idempotencyKey(prepared.getPrepared() == null ? null : prepared.getPrepared().getIdempotencyKey())
                .actionDigest(prepared.getPrepared() == null ? null : prepared.getPrepared().getActionDigest())
                .errorCode(errorCode)
                .retryable(retryable == null ? false : retryable)
                .resultSummary(resultSummary)
                .startedAt(Instant.now())
                .endedAt(Instant.now())
                .build();
        persistence.saveToolCall(record);
    }

    private void completeToolCall(String callId, AgentToolCallStatus status,
                                  String errorCode, boolean retryable, String resultSummary) {
        AgentToolCallRecord current = persistence.findToolCall(callId).orElse(null);
        if (current == null) {
            return;
        }
        persistence.updateToolCall(current.withStatus(status)
                .withErrorCode(errorCode)
                .withRetryable(retryable)
                .withResultSummary(resultSummary)
                .withEndedAt(Instant.now()));
    }

    private JsonNode parseArgs(AgentModelToolCall call) {
        try {
            return mapper.readTree(call.getArguments());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private ObjectNode modelUsagePayload(AgentModelRequest request, AgentModelResponse response) {
        ObjectNode node = AgentEventFactory.payload();
        node.put("provider", request.getProvider() == null ? "unknown" : request.getProvider());
        node.put("model", request.getModel() == null ? "unknown" : request.getModel());
        if (response.getUsage() != null) {
            node.put("promptTokens", response.getUsage().getPromptTokens());
            node.put("completionTokens", response.getUsage().getCompletionTokens());
        } else {
            node.put("promptTokens", 0).put("completionTokens", 0);
        }
        node.put("finishReason", response.getFinishReason() == null ? "" : response.getFinishReason().name());
        node.put("durationMs", response.getDurationMillis());
        return node;
    }

    private ObjectNode blockedPayload(AgentModelToolCall call, PreflightResult prepared) {
        return AgentEventFactory.payload()
                .put("callId", call.getId())
                .put("errorCode", prepared.getErrorCode())
                .put("safeMessage", prepared.getSafeMessage());
    }

    private boolean isResumable(AgentSessionStatus status) {
        return status == AgentSessionStatus.WAITING_INPUT
                || status == AgentSessionStatus.WAITING_SECRET
                || status == AgentSessionStatus.WAITING_APPROVAL
                || status == AgentSessionStatus.FAILED;
    }

    private static AgentLoopResult result(AgentLoopOutcome outcome,
                                          AgentSessionStatus sessionStatus,
                                          AgentRunStatus runStatus,
                                          long lastSeq,
                                          String errorCode,
                                          boolean retryable) {
        return AgentLoopResult.builder()
                .outcome(outcome)
                .sessionStatus(sessionStatus)
                .runStatus(runStatus)
                .lastSeq(lastSeq)
                .errorCode(errorCode)
                .retryable(retryable)
                .build();
    }

    private static final class NoopListener implements AgentModelEventListener {
    }

    private enum NoopSecretResolver implements com.consilens.agent.api.tool.AgentSecretResolver {
        INSTANCE;

        @Override
        public char[] resolve(String secretRequestId) {
            return new char[0];
        }

        @Override
        public void consume(String secretRequestId) {
            // no-op for WP-05 loop tests
        }
    }
}
