package com.consilens.agent.core.store;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.plan.AgentPlanAction;
import com.consilens.agent.api.plan.AgentPlanActionType;
import com.consilens.agent.api.plan.AgentPlanStatus;
import com.consilens.agent.api.plan.AgentProvisioningPlan;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.state.AgentWorkingState;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentApprovalStatus;
import com.consilens.agent.api.store.AgentPersistence;
import com.consilens.agent.api.store.AgentResourceRef;
import com.consilens.agent.api.store.AgentResourceType;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.api.store.AgentSnapshotRecord;
import com.consilens.agent.api.store.AgentToolCallRecord;
import com.consilens.agent.api.tool.AgentToolCallStatus;
import com.consilens.agent.api.tool.ToolRiskLevel;
import com.consilens.agent.core.event.AgentEventFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared contract for every AgentPersistence implementation (in-memory now,
 * MyBatis later). Each test gets a fresh persistence instance.
 */
public abstract class AgentPersistenceContractTest {

    protected abstract AgentPersistence newPersistence();

    private AgentSessionRecord newSession(String id, String actorId, String requestId) {
        return AgentSessionRecord.builder()
                .id(id)
                .actorId(actorId)
                .requestId(requestId)
                .status(AgentSessionStatus.READY)
                .workflowStage(AgentWorkflowStage.DISCOVERY)
                .nextSeq(0)
                .snapshotSeq(0)
                .version(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void seqStrictlyIncrementsAcrossAppends() {
        AgentPersistence persistence = newPersistence();
        AgentSessionRecord session = persistence.createSession(newSession("s1", "actor", "r1"));

        List<AgentEvent> first = persistence.appendEvents("s1", session.getNextSeq(), List.of(
                AgentEventFactory.create(AgentEventType.USER_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                        "s1", "run1", null, AgentEventFactory.payload().put("text", "hi"))));
        List<AgentEvent> second = persistence.appendEvents("s1", first.get(first.size() - 1).getSeq() + 1, List.of(
                AgentEventFactory.create(AgentEventType.TURN_STARTED, AgentEventVisibility.AUDIT_ONLY,
                        "s1", "run1", "turn1", null)));

        assertEquals(0, first.get(0).getSeq());
        assertEquals(1, second.get(0).getSeq());
        assertEquals(2, persistence.nextSeq("s1"));
    }

    @Test
    void appendWithStaleExpectedSeqIsRejected() {
        AgentPersistence persistence = newPersistence();
        persistence.createSession(newSession("s1", "actor", "r1"));

        List<AgentEvent> first = persistence.appendEvents("s1", 0, List.of(
                AgentEventFactory.create(AgentEventType.USER_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                        "s1", "run1", null, null)));
        assertNotNull(first);
        assertNull(persistence.appendEvents("s1", 0, List.of(
                AgentEventFactory.create(AgentEventType.TURN_STARTED, AgentEventVisibility.AUDIT_ONLY,
                        "s1", "run1", "turn1", null))));
    }

    @Test
    void createSessionIsIdempotentByActorAndRequestId() {
        AgentPersistence persistence = newPersistence();
        AgentSessionRecord first = persistence.createSession(newSession("s1", "actor", "req-1"));
        AgentSessionRecord duplicate = persistence.createSession(newSession("s2", "actor", "req-1"));

        assertEquals(first.getId(), duplicate.getId());
        assertTrue(persistence.findSession("s2").isEmpty());
    }

    @Test
    void leaseIsExclusiveAndExpires() {
        AgentPersistence persistence = newPersistence();
        persistence.createSession(newSession("s1", "actor", "r1"));
        Instant now = Instant.now();

        assertTrue(persistence.tryAcquireLease("s1", "actor", "worker-1", now, now.plusSeconds(30)));
        assertFalse(persistence.tryAcquireLease("s1", "actor", "worker-2", now, now.plusSeconds(30)));
        assertTrue(persistence.tryAcquireLease("s1", "actor", "worker-2", now.plusSeconds(31), now.plusSeconds(61)));
        assertFalse(persistence.tryAcquireLease("s1", "other-actor", "worker-3", now, now.plusSeconds(30)));
    }

    @Test
    void leaseRenewalRequiresOwnerAndExpectedVersion() {
        AgentPersistence persistence = newPersistence();
        AgentSessionRecord session = persistence.createSession(newSession("s1", "actor", "r1"));
        Instant now = Instant.now();
        persistence.tryAcquireLease("s1", "actor", "worker-1", now, now.plusSeconds(30));

        AgentSessionRecord leased = persistence.findSession("s1").orElseThrow();
        assertTrue(persistence.renewLease("s1", "worker-1", leased.getVersion(), now.plusSeconds(60)));
        assertFalse(persistence.renewLease("s1", "worker-1", leased.getVersion(), now.plusSeconds(60)));
        assertFalse(persistence.renewLease("s1", "worker-2", leased.getVersion() + 1, now.plusSeconds(60)));
    }

    @Test
    void requestIdDeduplicationIsEnforcedForRuns() {
        AgentPersistence persistence = newPersistence();
        persistence.createSession(newSession("s1", "actor", "r1"));
        AgentRunRecord run = AgentRunRecord.builder()
                .runId("run-1")
                .sessionId("s1")
                .requestId("msg-1")
                .status(AgentRunStatus.QUEUED)
                .startedAt(Instant.now())
                .build();

        persistence.createRun(run);
        assertTrue(persistence.findRunByRequestId("s1", "msg-1").isPresent());
        assertThrows(IllegalStateException.class, () -> persistence.createRun(run));
    }

    @Test
    void approvalCanOnlyBeDecidedOnce() {
        AgentPersistence persistence = newPersistence();
        persistence.createSession(newSession("s1", "actor", "r1"));
        AgentApprovalRecord approval = persistence.createApproval(AgentApprovalRecord.builder()
                .approvalId("ap-1")
                .sessionId("s1")
                .proposedRunId("run-1")
                .status(AgentApprovalStatus.PENDING)
                .actionDigest("digest-1")
                .safeSummary("create datasource")
                .expiresAt(Instant.now().plusSeconds(300))
                .build());

        assertTrue(persistence.decideApproval("ap-1", "actor", approval.getVersion(),
                AgentApprovalDecision.APPROVE, Instant.now()));
        assertFalse(persistence.decideApproval("ap-1", "actor", approval.getVersion(),
                AgentApprovalDecision.DENY, Instant.now()));
        assertEquals(AgentApprovalStatus.APPROVED, persistence.findApproval("ap-1").orElseThrow().getStatus());
    }

    @Test
    void snapshotCommitAdvancesSessionAtomically() {
        AgentPersistence persistence = newPersistence();
        AgentSessionRecord session = persistence.createSession(newSession("s1", "actor", "r1"));
        AgentWorkingState state = AgentWorkingState.builder()
                .objectiveId("obj-1")
                .objective("objective")
                .stage(AgentWorkflowStage.COLLECTING_DATASOURCES)
                .build();

        assertTrue(persistence.saveSnapshot("s1", 3, state, "summary", 1, session.getVersion()));
        assertTrue(persistence.saveSnapshot("s1", 7, state, "summary-2", 1, session.getVersion() + 1));
        assertFalse(persistence.saveSnapshot("s1", 8, state, "stale", 1, session.getVersion()));

        AgentSnapshotRecord latest = persistence.latestSnapshot("s1").orElseThrow();
        assertEquals(7, latest.getSeq());
        assertEquals(7, persistence.findSession("s1").orElseThrow().getSnapshotSeq());
    }

    @Test
    void succeededToolCallIsFindableBySessionAndIdempotencyKey() {
        AgentPersistence persistence = newPersistence();
        persistence.createSession(newSession("s1", "actor", "r1"));
        AgentToolCallRecord record = persistence.saveToolCall(AgentToolCallRecord.builder()
                .callId("call-1")
                .sessionId("s1")
                .runId("run-1")
                .turnId("turn-1")
                .sourceOrder(0)
                .toolName("create_datasource")
                .status(AgentToolCallStatus.SUCCEEDED)
                .riskLevel(ToolRiskLevel.HIGH)
                .idempotencyKey("idem-1")
                .startedAt(Instant.now())
                .endedAt(Instant.now())
                .build());

        assertTrue(persistence.findSucceededByIdempotencyKey("s1", "idem-1").isPresent());
        assertTrue(persistence.findSucceededByIdempotencyKey("s1", "missing").isEmpty());
        assertTrue(persistence.findSucceededByIdempotencyKey("other-session", "idem-1").isEmpty());
        assertEquals(record.getCallId(), persistence.findSucceededByIdempotencyKey("s1", "idem-1")
                .orElseThrow().getCallId());
    }

    @Test
    void planActionCanOnlyCompleteWithCurrentVersion() {
        AgentPersistence persistence = newPersistence();
        AgentSessionRecord session = persistence.createSession(newSession("s1", "actor", "r1"));
        AgentProvisioningPlan plan = AgentProvisioningPlan.builder()
                .planId("plan-1")
                .sessionId("s1")
                .objectiveId("obj-1")
                .status(AgentPlanStatus.PREPARED)
                .planDigest("digest")
                .actions(List.of(AgentPlanAction.builder()
                        .actionId("a1")
                        .sequence(1)
                        .actionType(AgentPlanActionType.CREATE_DATASOURCE)
                        .name("ds-1")
                        .build()))
                .createdAt(Instant.now())
                .build();

        assertTrue(persistence.saveReadyPlan("s1", plan, session.getVersion()));
        // Plan is stored with version 0; completing with the current version wins.
        assertTrue(persistence.completePlanAction("plan-1", "a1",
                AgentResourceRef.builder().resourceType(AgentResourceType.DATASOURCE).resourceId("12").build(),
                0));
        // A stale retry with the previous version must fail (CAS).
        assertFalse(persistence.completePlanAction("plan-1", "a1",
                AgentResourceRef.builder().resourceType(AgentResourceType.DATASOURCE).resourceId("99").build(),
                0));

        AgentPlanAction completed = persistence.findPlan("plan-1").orElseThrow().getActions().get(0);
        assertEquals("12", completed.getResourceId());
    }
}
