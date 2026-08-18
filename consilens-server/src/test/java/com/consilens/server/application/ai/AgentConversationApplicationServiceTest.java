package com.consilens.server.application.ai;

import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.store.InMemoryAgentPersistence;
import com.consilens.server.application.ai.approval.AgentApprovalService;
import com.consilens.server.api.dto.ai.SendAgentMessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConversationApplicationServiceTest {

    private InMemoryAgentPersistence persistence;
    private AgentConversationApplicationService service;

    @BeforeEach
    void setUp() {
        persistence = new InMemoryAgentPersistence();
        AgentRunEnqueueService enqueue = new AgentRunEnqueueService(persistence);
        service = new AgentConversationApplicationService(persistence, enqueue,
                new AgentApprovalService(persistence));
        persistence.createSession(AgentSessionRecord.builder()
                .id("s1").actorId("actor").requestId("create-1")
                .status(AgentSessionStatus.READY)
                .workflowStage(AgentWorkflowStage.DISCOVERY)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    @Test
    void messageCreatesQueuedRun() {
        SendAgentMessageResponse response = service.sendMessage("s1", "actor", "m1", "hello");
        assertEquals("ACCEPTED", response.getStatus());
        assertTrue(persistence.findRunByRequestId("s1", "m1").isPresent());
    }

    @Test
    void runningSessionQueuesSteeringInsteadOfNewRun() {
        persistence.updateSession(persistence.findSession("s1").orElseThrow()
                .withStatus(AgentSessionStatus.RUNNING));
        SendAgentMessageResponse response = service.sendMessage("s1", "actor", "m2", "steering");
        assertEquals("STEERING_QUEUED", response.getStatus());
        assertTrue(persistence.findRunByRequestId("s1", "m2").isEmpty());
        assertTrue(persistence.listEventsAfter("s1", -1, 10).stream()
                .anyMatch(e -> e.getType().name().equals("STEERING_QUEUED")));
    }

    @Test
    void replayingRequestIdIsIdempotent() {
        service.sendMessage("s1", "actor", "m3", "hello");
        SendAgentMessageResponse replayed = service.sendMessage("s1", "actor", "m3", "again");
        assertEquals("ALREADY_PROCESSED", replayed.getStatus());
    }

    @Test
    void denyingApprovalReturnsSessionToReady() {
        com.consilens.agent.api.store.AgentApprovalRecord approval =
                persistence.createApproval(com.consilens.agent.api.store.AgentApprovalRecord.builder()
                        .approvalId("ap-1").sessionId("s1").proposedRunId("plan-1")
                        .status(com.consilens.agent.api.store.AgentApprovalStatus.PENDING)
                        .actionDigest("digest-1").safeSummary("x").expiresAt(Instant.now().plusSeconds(300))
                        .build());
        persistence.updateSession(persistence.findSession("s1").orElseThrow()
                .withStatus(AgentSessionStatus.WAITING_APPROVAL).withActiveRunId("run-1"));

        service.decideApproval("s1", "actor", "ap-1", "dec-1",
                AgentApprovalDecision.DENY, "digest-1", approval.getVersion());

        assertEquals(AgentSessionStatus.READY,
                persistence.findSession("s1").orElseThrow().getStatus());
    }

    @Test
    void eventsRequireSessionOwnership() {
        assertThrows(com.consilens.server.domain.exception.ResourceNotFoundException.class,
                () -> service.events("s1", "wrong-actor", 0));
        assertEquals(0, service.events("s1", "actor", 0).size());
    }
}
