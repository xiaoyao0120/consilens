package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.event.AgentEvent;
import com.consilens.agent.api.event.AgentEventType;
import com.consilens.agent.api.event.AgentEventVisibility;
import com.consilens.agent.api.state.AgentSessionStatus;
import com.consilens.agent.api.state.AgentWorkflowStage;
import com.consilens.agent.api.store.AgentApprovalDecision;
import com.consilens.agent.api.store.AgentApprovalRecord;
import com.consilens.agent.api.store.AgentApprovalStatus;
import com.consilens.agent.api.store.AgentSessionRecord;
import com.consilens.agent.core.event.AgentEventFactory;
import com.consilens.server.infrastructure.db.mapper.ai.AiApprovalMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiEventMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanActionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiPlanMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiRunMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSecretMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSessionMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiSnapshotMapper;
import com.consilens.server.infrastructure.db.mapper.ai.AiToolCallMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-database concurrency checks (design WP-06): lease exclusivity, event
 * seq CAS and approval CAS must hold with workers hammering the same session.
 */
class MyBatisAgentPersistenceConcurrencyTest {

    private MyBatisAgentPersistence persistence;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:ai_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver", url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        Configuration configuration = new Configuration(new Environment("ai-concurrency",
                new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiSessionMapper.class);
        configuration.addMapper(AiEventMapper.class);
        configuration.addMapper(AiRunMapper.class);
        configuration.addMapper(AiToolCallMapper.class);
        configuration.addMapper(AiApprovalMapper.class);
        configuration.addMapper(AiPlanMapper.class);
        configuration.addMapper(AiPlanActionMapper.class);
        configuration.addMapper(AiSnapshotMapper.class);
        configuration.addMapper(AiSecretMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        persistence = new MyBatisAgentPersistence(factory);
        persistence.createSession(AgentSessionRecord.builder()
                .id("s1").actorId("actor").requestId("create-1")
                .status(AgentSessionStatus.READY)
                .workflowStage(AgentWorkflowStage.DISCOVERY)
                .nextSeq(0).snapshotSeq(0).version(0)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build());
    }

    @Test
    void onlyOneWorkerCanAcquireTheLease() throws Exception {
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final String worker = "worker-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    Instant now = Instant.now();
                    boolean won = persistence.tryAcquireLease("s1", "actor", worker,
                            now, now.plusSeconds(30));
                    if (won) {
                        acquired.incrementAndGet();
                    }
                    return won;
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertEquals(1, acquired.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentEventAppendsNeverDuplicateSeq() throws Exception {
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    long expected = persistence.nextSeq("s1");
                    AgentEvent event = AgentEventFactory.create(
                            AgentEventType.USER_MESSAGE, AgentEventVisibility.USER_AND_MODEL,
                            "s1", "run-x", null,
                            AgentEventFactory.payload().put("text", "x"));
                    List<AgentEvent> persisted = persistence.appendEvents("s1", expected, List.of(event));
                    if (persisted == null) {
                        conflicts.incrementAndGet();
                        return false;
                    }
                    successes.incrementAndGet();
                    return true;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<Boolean> future : futures) {
                future.get();
            }
            assertTrue(successes.get() >= 1);
            assertEquals(workers, successes.get() + conflicts.get());
            List<AgentEvent> events = persistence.listEventsAfter("s1", -1, 1000);
            assertEquals(successes.get(), events.size());
            List<Long> seqs = events.stream().map(AgentEvent::getSeq).sorted()
                    .collect(Collectors.toList());
            assertEquals(seqs.size(), seqs.stream().distinct().count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void approvalDecisionIsAtomicUnderContention() throws Exception {
        AgentApprovalRecord approval = persistence.createApproval(AgentApprovalRecord.builder()
                .approvalId("ap-1").sessionId("s1").proposedRunId("run-1")
                .status(AgentApprovalStatus.PENDING)
                .actionDigest("digest-1").safeSummary("create datasource")
                .expiresAt(Instant.now().plusSeconds(300)).build());
        long version = approval.getVersion();

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger decided = new AtomicInteger();
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    boolean won = persistence.decideApproval("ap-1", "actor", version,
                            AgentApprovalDecision.APPROVE, Instant.now());
                    if (won) {
                        decided.incrementAndGet();
                    }
                    return won;
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
            assertEquals(1, decided.get());
            assertEquals(AgentApprovalStatus.APPROVED,
                    persistence.findApproval("ap-1").orElseThrow().getStatus());
        } finally {
            pool.shutdownNow();
        }
    }
}
