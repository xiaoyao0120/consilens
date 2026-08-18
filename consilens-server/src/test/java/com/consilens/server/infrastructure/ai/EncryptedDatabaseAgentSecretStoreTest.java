package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentSecretEnvelope;
import com.consilens.server.infrastructure.db.mapper.ai.AiSecretMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncryptedDatabaseAgentSecretStoreTest {

    private EncryptedDatabaseAgentSecretStore store;
    private AgentSecretCipher cipher;

    @BeforeEach
    void setUp() {
        SqlSessionFactory factory = AiTestSupport.newSessionFactory("ai_secret_" + UUID.randomUUID());
        cipher = new AgentSecretCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[32]), "k1");
        AiSecretMapper mapper = new SqlSessionTemplate(factory).getMapper(AiSecretMapper.class);
        store = new EncryptedDatabaseAgentSecretStore(mapper, cipher);
    }

    @Test
    void fulfillResolveConsumeRoundTrip() {
        String id = store.createRequest("actor", "session", "draft_source",
                Instant.now().plusSeconds(600), 8);
        AgentSecretEnvelope envelope = cipher.encrypt("p@ss".toCharArray(),
                AgentSecretCipher.aad("actor", "session", id));
        assertTrue(store.fulfill(id, "actor", "session", envelope));

        Optional<char[]> resolved = store.resolve(id, "actor", "session", "probe", Instant.now());
        assertTrue(resolved.isPresent());
        assertArrayEquals("p@ss".toCharArray(), resolved.get());
        assertTrue(store.consume(id, "actor", "session"));
        assertTrue(store.resolve(id, "actor", "session", "probe", Instant.now()).isEmpty());
    }

    @Test
    void doubleFulfillIsRejected() {
        String id = store.createRequest("actor", "session", "draft",
                Instant.now().plusSeconds(600), 8);
        AgentSecretEnvelope envelope = cipher.encrypt("a".toCharArray(),
                AgentSecretCipher.aad("actor", "session", id));
        assertTrue(store.fulfill(id, "actor", "session", envelope));
        assertFalse(store.fulfill(id, "actor", "session", envelope));
    }

    @Test
    void readLimitAndExpiryBlockResolution() {
        String id = store.createRequest("actor", "session", "draft",
                Instant.now().plusSeconds(600), 1);
        AgentSecretEnvelope envelope = cipher.encrypt("x".toCharArray(),
                AgentSecretCipher.aad("actor", "session", id));
        store.fulfill(id, "actor", "session", envelope);
        assertTrue(store.resolve(id, "actor", "session", "probe", Instant.now()).isPresent());
        assertTrue(store.resolve(id, "actor", "session", "probe", Instant.now()).isEmpty());

        String expired = store.createRequest("actor", "session", "draft",
                Instant.now().minusSeconds(5), 8);
        store.fulfill(expired, "actor", "session",
                cipher.encrypt("y".toCharArray(), AgentSecretCipher.aad("actor", "session", expired)));
        assertTrue(store.resolve(expired, "actor", "session", "probe", Instant.now()).isEmpty());
    }

    @Test
    void actorBindingPreventsCrossSessionReads() {
        String id = store.createRequest("actor", "session", "draft",
                Instant.now().plusSeconds(600), 8);
        store.fulfill(id, "actor", "session",
                cipher.encrypt("z".toCharArray(), AgentSecretCipher.aad("actor", "session", id)));
        assertTrue(store.resolve(id, "other", "session", "probe", Instant.now()).isEmpty());
        assertTrue(store.resolve(id, "actor", "other-session", "probe", Instant.now()).isEmpty());
        assertTrue(store.revoke(id, "actor", "session"));
        assertTrue(store.resolve(id, "actor", "session", "probe", Instant.now()).isEmpty());
    }

    @Test
    void expiredRequestCannotBeFulfilled() {
        String id = store.createRequest("actor", "session", "draft",
                Instant.now().minusSeconds(5), 8);
        AgentSecretEnvelope envelope = cipher.encrypt("x".toCharArray(),
                AgentSecretCipher.aad("actor", "session", id));
        assertFalse(store.fulfill(id, "actor", "session", envelope));
    }
}
