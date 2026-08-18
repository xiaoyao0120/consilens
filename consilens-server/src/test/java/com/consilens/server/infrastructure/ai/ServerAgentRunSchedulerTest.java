package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.model.AgentCancellationToken;
import com.consilens.agent.api.state.AgentRunStatus;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.store.AgentRunRecord;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.runtime.AgentRunWorker;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.infrastructure.db.mapper.ai.AiRunMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAgentRunSchedulerTest {

    private MyBatisAgentPersistence persistence;
    private AiRunMapper runMapper;
    private ServerAgentRunScheduler scheduler;
    private AgentRunWorker noopWorker;

    @BeforeEach
    void setUp() {
        SqlSessionFactory factory = AiTestSupport.newSessionFactory("ai_scheduler_" + UUID.randomUUID());
        persistence = new MyBatisAgentPersistence(factory);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        runMapper = template.getMapper(AiRunMapper.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getAi().setLeaseSeconds(30);
        noopWorker = (run, token) -> {
        };
        scheduler = new ServerAgentRunScheduler(runMapper, persistence, properties, noopWorker);
        persistence.createSession(AgentSessionRecord.builder()
                .id("s1").actorId("actor").requestId("create-1")
                .status(AgentSessionStatus.READY)
                .workflowStage(AgentWorkflowStage.DISCOVERY)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    private AgentRunRecord enqueue(String runId, String requestId) {
        return persistence.createRun(AgentRunRecord.builder()
                .runId(runId)
                .sessionId("s1")
                .requestId(requestId)
                .status(AgentRunStatus.QUEUED)
                .startedAt(Instant.now())
                .build());
    }

    @Test
    void claimsTheOldestQueuedRunAndHoldsSessionLease() {
        enqueue("run-1", "req-1");
        enqueue("run-2", "req-2");

        Optional<AgentRunRecord> claimed = scheduler.claimNextRun("worker-a", Instant.now());

        assertTrue(claimed.isPresent());
        assertEquals("run-1", claimed.get().getRunId());
        assertEquals(AgentRunStatus.RUNNING, claimed.get().getStatus());
        assertEquals("worker-a", claimed.get().getLeaseOwner());
        // Second worker cannot claim the second run of the same session.
        assertTrue(scheduler.claimNextRun("worker-b", Instant.now()).isEmpty());
    }

    @Test
    void renewAndReleaseRequireTheClaimingWorker() {
        enqueue("run-1", "req-1");
        enqueue("run-2", "req-2");
        AgentRunRecord claimed = scheduler.claimNextRun("worker-a", Instant.now()).orElseThrow();

        assertFalse(scheduler.renewRun("run-1", "worker-b", claimed.getVersion(), Instant.now().plusSeconds(30)));
        assertTrue(scheduler.renewRun("run-1", "worker-a", claimed.getVersion(), Instant.now().plusSeconds(30)));
        scheduler.releaseRun("run-1", "worker-a");
        assertEquals(AgentRunStatus.RUNNING,
                persistence.findRun("run-1").orElseThrow().getStatus());
        // The loop releases the session lease when the run finishes; mirror that.
        persistence.releaseLease("s1", "worker-a");
        Optional<AgentRunRecord> next = scheduler.claimNextRun("worker-b", Instant.now());
        assertTrue(next.isPresent());
        assertEquals("run-2", next.get().getRunId());
    }

    @Test
    void workerDelegatesToTheInjectedWorker() {
        assertEquals(noopWorker, scheduler.worker());
        noopWorker.process(AgentRunRecord.builder().runId("r").sessionId("s1").build(),
                AgentCancellationToken.NEVER_CANCELLED);
    }
}
