package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.event.AgentEventFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Spring wiring smoke test: the durable persistence bean is created from the
 * application SqlSessionFactory and the AI tables exist in the H2 schema.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "consilens.server.scheduler.enabled=false",
        "consilens.server.security.enabled=false",
        "consilens.server.database.allow-embedded=true",
        "spring.sql.init.mode=always"
})
class MyBatisAgentPersistenceSpringTest {

    @Autowired
    private MyBatisAgentPersistence persistence;

    @Test
    void springContextRunsLeaseAndEventRoundTrip() {
        AgentSessionRecord session = persistence.createSession(AgentSessionRecord.builder()
                .id("spring-s1").actorId("actor").requestId("spring-create-1")
                .status(AgentSessionStatus.READY)
                .workflowStage(AgentWorkflowStage.DISCOVERY)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());

        Instant now = Instant.now();
        assertTrue(persistence.tryAcquireLease("spring-s1", "actor", "worker", now, now.plusSeconds(30)));
        List<AgentEvent> persisted = persistence.appendEvents("spring-s1", 0, List.of(
                AgentEventFactory.create(AgentEventType.USER_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                        "spring-s1", "run-1", null,
                        AgentEventFactory.payload().put("text", "hello"))));
        assertEquals(1, persisted.size());
        assertEquals(0, persisted.get(0).getSeq());
        assertEquals(1, persistence.nextSeq("spring-s1"));
        assertTrue(persistence.listEventsAfter("spring-s1", 0, 10).isEmpty());
        assertEquals(1, persistence.listEventsAfter("spring-s1", -1, 10).size());
        assertTrue(persistence.releaseLease("spring-s1", "worker"));
    }

    @Test
    void deleteSessionRemovesSessionAndCascadedRows() {
        AgentSessionRecord session = persistence.createSession(AgentSessionRecord.builder()
                .id("spring-del").actorId("actor").requestId("spring-del-1")
                .status(AgentSessionStatus.READY)
                .workflowStage(AgentWorkflowStage.DISCOVERY)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
        persistence.appendEvents("spring-del", 0, List.of(
                AgentEventFactory.create(AgentEventType.USER_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                        "spring-del", "run-1", null,
                        AgentEventFactory.payload().put("text", "hello"))));

        persistence.deleteSession("spring-del");

        assertFalse(persistence.findSession("spring-del").isPresent());
        assertTrue(persistence.listEventsAfter("spring-del", -1, 10).isEmpty());
    }
}
