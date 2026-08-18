package com.consilens.agent.api.store;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentWorkingState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable event store + materialized snapshots + lease control. The core loop
 * depends only on this port; every mutating method expresses its concurrency
 * semantics explicitly. Implementations must return false (never throw) on
 * CAS/lease conflicts so the loop can react deterministically.
 */
public interface AgentPersistence {

    // Session

    Optional<AgentSessionRecord> findSession(String sessionId);

    /**
     * Idempotent by (actorId, requestId): returns the existing session when a
     * duplicate creation request arrives, otherwise persists a new session.
     */
    AgentSessionRecord createSession(AgentSessionRecord session);

    boolean updateSession(AgentSessionRecord session);

    /** 级联删除会话及其全部事件/运行/快照/审批/凭据/计划/工具调用记录。 */
    void deleteSession(String sessionId);

    // Lease

    boolean tryAcquireLease(String sessionId, String actorId, String workerId,
                            Instant now, Instant expiresAt);

    boolean renewLease(String sessionId, String workerId, long expectedVersion, Instant expiresAt);

    boolean releaseLease(String sessionId, String workerId);

    // Events

    /**
     * Appends events atomically starting at {@code expectedNextSeq}; returns
     * the persisted events with assigned seq. Returns null on seq conflict.
     */
    List<AgentEvent> appendEvents(String sessionId, long expectedNextSeq, List<AgentEvent> events);

    List<AgentEvent> listEventsAfter(String sessionId, long afterSeq, int limit);

    long nextSeq(String sessionId);

    // Run

    Optional<AgentRunRecord> findRunByRequestId(String sessionId, String requestId);

    Optional<AgentRunRecord> findRun(String runId);

    /** Throws on duplicate runId; the caller must first check requestId. */
    AgentRunRecord createRun(AgentRunRecord run);

    boolean updateRun(AgentRunRecord run);

    // Tool call

    Optional<AgentToolCallRecord> findSucceededByIdempotencyKey(String sessionId, String idempotencyKey);

    Optional<AgentToolCallRecord> findToolCall(String callId);

    AgentToolCallRecord saveToolCall(AgentToolCallRecord record);

    boolean updateToolCall(AgentToolCallRecord record);

    // Approval

    AgentApprovalRecord createApproval(AgentApprovalRecord approval);

    Optional<AgentApprovalRecord> findApproval(String approvalId);

    /** CAS on PENDING + version; only one decision can ever win. */
    boolean decideApproval(String approvalId, String actorId, long expectedVersion,
                           AgentApprovalDecision decision, Instant now);

    // Plan

    boolean saveReadyPlan(String sessionId, AgentProvisioningPlan plan, long expectedSessionVersion);

    Optional<AgentProvisioningPlan> findPlan(String planId);

    boolean claimApprovedPlan(String planId, long expectedVersion, String workerId);

    /** Marks a PREPARED plan APPROVED after its approval is granted. */
    boolean approvePlan(String planId, long expectedVersion);

    boolean completePlanAction(String planId, String actionId, AgentResourceRef resourceRef,
                               long expectedVersion);

    // Snapshot

    boolean saveSnapshot(String sessionId, long seq, AgentWorkingState workingState,
                         String conversationSummary, int schemaVersion, long expectedSessionVersion);

    Optional<AgentSnapshotRecord> latestSnapshot(String sessionId);
}
