package com.consilens.server.application.ai.approval;

import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentApprovalStatus;
import com.consilens.agent.core.store.InMemoryAgentPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentApprovalServiceTest {

    private final InMemoryAgentPersistence persistence = new InMemoryAgentPersistence();
    private final AgentApprovalService service = new AgentApprovalService(persistence);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        persistence.createSession(com.consilens.agent.api.store.AgentSessionRecord.builder()
                .id("s1").actorId("actor").requestId("create-1")
                .status(com.consilens.agent.api.state.AgentSessionStatus.READY)
                .workflowStage(com.consilens.agent.api.state.AgentWorkflowStage.DISCOVERY)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    @Test
    void digestIsDeterministicAndSensitiveToArguments() throws Exception {
        String first = service.computeActionDigest("create_datasource",
                mapper.readTree("{\"host\":\"a\",\"port\":3306}"),
                Map.of("schema", "1"), "v1");
        String second = service.computeActionDigest("create_datasource",
                mapper.readTree("{\"port\":3306,\"host\":\"a\"}"),
                Map.of("schema", "1"), "v1");
        String changed = service.computeActionDigest("create_datasource",
                mapper.readTree("{\"host\":\"b\",\"port\":3306}"),
                Map.of("schema", "1"), "v1");
        assertEquals(first, second);
        assertNotEquals(first, changed);
    }

    @Test
    void approveWithMatchingDigestSucceedsOnce() {
        String digest = service.computeActionDigest("write",
                mapper.createObjectNode(), Map.of(), "v1");
        AgentApprovalRecord approval = service.createApproval("s1", "run-1", digest,
                "创建数据源", "[]", Instant.now().plusSeconds(300));

        AgentApprovalService.DecideOutcome outcome = service.decide(
                approval.getApprovalId(), "actor", approval.getVersion(),
                AgentApprovalDecision.APPROVE, digest, Instant.now());
        assertTrue(outcome.succeeded());
        assertEquals(AgentApprovalStatus.APPROVED, outcome.status());

        AgentApprovalService.DecideOutcome again = service.decide(
                approval.getApprovalId(), "actor", approval.getVersion() + 1,
                AgentApprovalDecision.DENY, digest, Instant.now());
        assertFalse(again.succeeded());
        assertEquals("APPROVAL_ALREADY_DECIDED", again.errorCode());
    }

    @Test
    void staleDigestOrExpiryRejectsDecision() {
        String digest = service.computeActionDigest("write",
                mapper.createObjectNode(), Map.of(), "v1");
        AgentApprovalRecord approval = service.createApproval("s1", "run-1", digest,
                "创建数据源", "[]", Instant.now().plusSeconds(300));

        AgentApprovalService.DecideOutcome stale = service.decide(
                approval.getApprovalId(), "actor", approval.getVersion(),
                AgentApprovalDecision.APPROVE, "wrong-digest", Instant.now());
        assertFalse(stale.succeeded());
        assertEquals("APPROVAL_STALE", stale.errorCode());

        AgentApprovalRecord expired = service.createApproval("s1", "run-2", digest,
                "创建任务", "[]", Instant.now().minusSeconds(5));
        AgentApprovalService.DecideOutcome expiredOutcome = service.decide(
                expired.getApprovalId(), "actor", expired.getVersion(),
                AgentApprovalDecision.APPROVE, digest, Instant.now());
        assertFalse(expiredOutcome.succeeded());
        assertEquals("APPROVAL_EXPIRED", expiredOutcome.errorCode());
    }
}
