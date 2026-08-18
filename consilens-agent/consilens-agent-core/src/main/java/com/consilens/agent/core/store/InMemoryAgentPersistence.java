package com.consilens.agent.core.store;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.plan.AgentPlanAction;
import com.consilens.agent.api.plan.AgentPlanActionStatus;
import com.consilens.agent.api.plan.AgentPlanStatus;
import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentApprovalStatus;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentResourceRef;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.api.store.AgentSnapshotRecord;
import com.consilens.agent.api.store.AgentToolCallRecord;
import com.consilens.agent.api.tool.AgentToolCallStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory persistence used by core loop tests and as the shared contract
 * test target. Single-JVM synchronization is acceptable here because this is
 * a test implementation; production must use database CAS, never JVM locks.
 */
public class InMemoryAgentPersistence implements AgentPersistence {

    private final Map<String, AgentSessionRecord> sessions = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Long, AgentEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, AgentRunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, AgentToolCallRecord> toolCalls = new ConcurrentHashMap<>();
    private final Map<String, AgentApprovalRecord> approvals = new ConcurrentHashMap<>();
    private final Map<String, AgentProvisioningPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, AgentSnapshotRecord> snapshots = new ConcurrentHashMap<>();

    private final Object monitor = new Object();

    @Override
    public Optional<AgentSessionRecord> findSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public AgentSessionRecord createSession(AgentSessionRecord session) {
        synchronized (monitor) {
            AgentSessionRecord existing = sessions.get(session.getId());
            if (existing != null) {
                return existing;
            }
            for (AgentSessionRecord candidate : sessions.values()) {
                if (candidate.getActorId().equals(session.getActorId())
                        && candidate.getRequestId() != null
                        && candidate.getRequestId().equals(session.getRequestId())) {
                    return candidate;
                }
            }
            AgentSessionRecord created = session.withVersion(1L).withCreatedAt(Instant.now())
                    .withUpdatedAt(Instant.now());
            sessions.put(created.getId(), created);
            events.put(created.getId(), new TreeMap<>());
            return created;
        }
    }

    @Override
    public boolean updateSession(AgentSessionRecord session) {
        synchronized (monitor) {
            AgentSessionRecord current = sessions.get(session.getId());
            if (current == null || current.getVersion() != session.getVersion()) {
                return false;
            }
            sessions.put(session.getId(), session.withVersion(current.getVersion() + 1)
                    .withUpdatedAt(Instant.now()));
            return true;
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        synchronized (monitor) {
            sessions.remove(sessionId);
            events.remove(sessionId);
            runs.entrySet().removeIf(e -> sessionId.equals(e.getValue().getSessionId()));
            toolCalls.entrySet().removeIf(e -> sessionId.equals(e.getValue().getSessionId()));
            approvals.entrySet().removeIf(e -> sessionId.equals(e.getValue().getSessionId()));
            plans.entrySet().removeIf(e -> sessionId.equals(e.getValue().getSessionId()));
            snapshots.entrySet().removeIf(e -> sessionId.equals(e.getValue().getSessionId()));
        }
    }

    @Override
    public boolean tryAcquireLease(String sessionId, String actorId, String workerId,
                                   Instant now, Instant expiresAt) {
        synchronized (monitor) {
            AgentSessionRecord current = sessions.get(sessionId);
            if (current == null || !current.getActorId().equals(actorId)) {
                return false;
            }
            boolean free = current.getLeaseOwner() == null
                    || current.getLeaseExpiresAt() == null
                    || !current.getLeaseExpiresAt().isAfter(now)
                    || current.getLeaseOwner().equals(workerId);
            if (!free) {
                return false;
            }
            sessions.put(sessionId, current.withLeaseOwner(workerId)
                    .withLeaseExpiresAt(expiresAt)
                    .withVersion(current.getVersion() + 1)
                    .withUpdatedAt(Instant.now()));
            return true;
        }
    }

    @Override
    public boolean renewLease(String sessionId, String workerId, long expectedVersion, Instant expiresAt) {
        synchronized (monitor) {
            AgentSessionRecord current = sessions.get(sessionId);
            if (current == null || current.getVersion() != expectedVersion
                    || !workerId.equals(current.getLeaseOwner())) {
                return false;
            }
            sessions.put(sessionId, current.withLeaseExpiresAt(expiresAt)
                    .withVersion(current.getVersion() + 1)
                    .withUpdatedAt(Instant.now()));
            return true;
        }
    }

    @Override
    public boolean releaseLease(String sessionId, String workerId) {
        synchronized (monitor) {
            AgentSessionRecord current = sessions.get(sessionId);
            if (current == null || !workerId.equals(current.getLeaseOwner())) {
                return false;
            }
            sessions.put(sessionId, current.withLeaseOwner(null)
                    .withLeaseExpiresAt(null)
                    .withVersion(current.getVersion() + 1)
                    .withUpdatedAt(Instant.now()));
            return true;
        }
    }

    @Override
    public List<AgentEvent> appendEvents(String sessionId, long expectedNextSeq, List<AgentEvent> newEvents) {
        synchronized (monitor) {
            AgentSessionRecord current = sessions.get(sessionId);
            if (current == null || current.getNextSeq() != expectedNextSeq) {
                return null;
            }
            TreeMap<Long, AgentEvent> sessionEvents = events.computeIfAbsent(sessionId, k -> new TreeMap<>());
            long seq = expectedNextSeq;
            List<AgentEvent> persisted = new ArrayList<>(newEvents.size());
            for (AgentEvent event : newEvents) {
                AgentEvent assigned = event.withSeq(seq++);
                sessionEvents.put(assigned.getSeq(), assigned);
                persisted.add(assigned);
            }
            sessions.put(sessionId, current.withNextSeq(seq).withVersion(current.getVersion() + 1)
                    .withUpdatedAt(Instant.now()));
            return persisted;
        }
    }

    @Override
    public List<AgentEvent> listEventsAfter(String sessionId, long afterSeq, int limit) {
        TreeMap<Long, AgentEvent> sessionEvents = events.get(sessionId);
        if (sessionEvents == null) {
            return List.of();
        }
        List<AgentEvent> result = new ArrayList<>();
        for (AgentEvent event : sessionEvents.tailMap(afterSeq, false).values()) {
            result.add(event);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    @Override
    public long nextSeq(String sessionId) {
        AgentSessionRecord session = sessions.get(sessionId);
        return session == null ? 0 : session.getNextSeq();
    }

    @Override
    public Optional<AgentRunRecord> findRunByRequestId(String sessionId, String requestId) {
        return runs.values().stream()
                .filter(r -> r.getSessionId().equals(sessionId) && r.getRequestId().equals(requestId))
                .findFirst();
    }

    @Override
    public Optional<AgentRunRecord> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public AgentRunRecord createRun(AgentRunRecord run) {
        synchronized (monitor) {
            if (runs.containsKey(run.getRunId())) {
                throw new IllegalStateException("run already exists: " + run.getRunId());
            }
            AgentRunRecord created = run.withVersion(1L);
            runs.put(created.getRunId(), created);
            return created;
        }
    }

    @Override
    public boolean updateRun(AgentRunRecord run) {
        synchronized (monitor) {
            AgentRunRecord current = runs.get(run.getRunId());
            if (current == null || current.getVersion() != run.getVersion()) {
                return false;
            }
            runs.put(run.getRunId(), run.withVersion(current.getVersion() + 1));
            return true;
        }
    }

    @Override
    public Optional<AgentToolCallRecord> findSucceededByIdempotencyKey(String sessionId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return toolCalls.values().stream()
                .filter(t -> t.getSessionId().equals(sessionId)
                        && idempotencyKey.equals(t.getIdempotencyKey())
                        && t.getStatus() == AgentToolCallStatus.SUCCEEDED)
                .findFirst();
    }

    @Override
    public Optional<AgentToolCallRecord> findToolCall(String callId) {
        return Optional.ofNullable(toolCalls.get(callId));
    }

    @Override
    public AgentToolCallRecord saveToolCall(AgentToolCallRecord record) {
        synchronized (monitor) {
            if (toolCalls.containsKey(record.getCallId())) {
                throw new IllegalStateException("tool call already exists: " + record.getCallId());
            }
            AgentToolCallRecord created = record.withVersion(1L);
            toolCalls.put(created.getCallId(), created);
            return created;
        }
    }

    @Override
    public boolean updateToolCall(AgentToolCallRecord record) {
        synchronized (monitor) {
            AgentToolCallRecord current = toolCalls.get(record.getCallId());
            if (current == null || current.getVersion() != record.getVersion()) {
                return false;
            }
            toolCalls.put(record.getCallId(), record.withVersion(current.getVersion() + 1));
            return true;
        }
    }

    @Override
    public AgentApprovalRecord createApproval(AgentApprovalRecord approval) {
        synchronized (monitor) {
            if (approvals.containsKey(approval.getApprovalId())) {
                throw new IllegalStateException("approval already exists: " + approval.getApprovalId());
            }
            AgentApprovalRecord created = approval.withVersion(1L);
            approvals.put(created.getApprovalId(), created);
            return created;
        }
    }

    @Override
    public Optional<AgentApprovalRecord> findApproval(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    @Override
    public boolean decideApproval(String approvalId, String actorId, long expectedVersion,
                                  AgentApprovalDecision decision, Instant now) {
        synchronized (monitor) {
            AgentApprovalRecord current = approvals.get(approvalId);
            if (current == null || current.getStatus() != AgentApprovalStatus.PENDING
                    || current.getVersion() != expectedVersion) {
                return false;
            }
            AgentApprovalStatus nextStatus = decision == AgentApprovalDecision.APPROVE
                    ? AgentApprovalStatus.APPROVED
                    : AgentApprovalStatus.DENIED;
            approvals.put(approvalId, current.withStatus(nextStatus)
                    .withActorId(actorId)
                    .withDecidedAt(now)
                    .withVersion(current.getVersion() + 1));
            return true;
        }
    }

    @Override
    public boolean saveReadyPlan(String sessionId, AgentProvisioningPlan plan, long expectedSessionVersion) {
        synchronized (monitor) {
            AgentSessionRecord current = sessions.get(sessionId);
            if (current == null || current.getVersion() != expectedSessionVersion) {
                return false;
            }
            plans.put(plan.getPlanId(), plan);
            sessions.put(sessionId, current.withVersion(current.getVersion() + 1)
                    .withUpdatedAt(Instant.now()));
            return true;
        }
    }

    @Override
    public Optional<AgentProvisioningPlan> findPlan(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public boolean claimApprovedPlan(String planId, long expectedVersion, String workerId) {
        synchronized (monitor) {
            AgentProvisioningPlan current = plans.get(planId);
            if (current == null || current.getStatus() != AgentPlanStatus.APPROVED
                    || current.getVersion() != expectedVersion) {
                return false;
            }
            plans.put(planId, current.withStatus(AgentPlanStatus.EXECUTING)
                    .withVersion(current.getVersion() + 1));
            return true;
        }
    }

    @Override
    public boolean approvePlan(String planId, long expectedVersion) {
        synchronized (monitor) {
            AgentProvisioningPlan current = plans.get(planId);
            if (current == null || current.getStatus() != AgentPlanStatus.PREPARED
                    || current.getVersion() != expectedVersion) {
                return false;
            }
            plans.put(planId, current.withStatus(AgentPlanStatus.APPROVED)
                    .withVersion(current.getVersion() + 1));
            return true;
        }
    }

    @Override
    public boolean completePlanAction(String planId, String actionId, AgentResourceRef resourceRef,
                                      long expectedVersion) {
        synchronized (monitor) {
            AgentProvisioningPlan current = plans.get(planId);
            if (current == null || current.getVersion() != expectedVersion) {
                return false;
            }
            List<AgentPlanAction> actions = new ArrayList<>();
            for (AgentPlanAction action : current.getActions()) {
                if (action.getActionId().equals(actionId)) {
                    actions.add(action.withStatus(AgentPlanActionStatus.SUCCEEDED)
                            .withResourceId(resourceRef == null ? null : resourceRef.getResourceId()));
                } else {
                    actions.add(action);
                }
            }
            plans.put(planId, current.withActions(actions).withVersion(current.getVersion() + 1));
            return true;
        }
    }

    @Override
    public boolean saveSnapshot(String sessionId, long seq, AgentWorkingState workingState,
                                String conversationSummary, int schemaVersion, long expectedSessionVersion) {
        synchronized (monitor) {
            AgentSessionRecord current = sessions.get(sessionId);
            if (current == null || current.getVersion() != expectedSessionVersion) {
                return false;
            }
            AgentSnapshotRecord snapshot = AgentSnapshotRecord.builder()
                    .sessionId(sessionId)
                    .seq(seq)
                    .workingState(workingState)
                    .conversationSummary(conversationSummary)
                    .schemaVersion(schemaVersion)
                    .createdAt(Instant.now())
                    .build();
            snapshots.put(sessionId + ":" + seq, snapshot);
            sessions.put(sessionId, current.withSnapshotSeq(seq)
                    .withVersion(current.getVersion() + 1)
                    .withUpdatedAt(Instant.now()));
            return true;
        }
    }

    @Override
    public Optional<AgentSnapshotRecord> latestSnapshot(String sessionId) {
        return snapshots.values().stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .max(Comparator.comparingLong(AgentSnapshotRecord::getSeq));
    }

    /**
     * Test helper: latest snapshot map copy for assertion convenience.
     */
    public Map<String, AgentSnapshotRecord> allSnapshots() {
        return new LinkedHashMap<>(snapshots);
    }
}
