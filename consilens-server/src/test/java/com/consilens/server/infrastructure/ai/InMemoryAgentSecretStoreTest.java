package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentSecretEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAgentSecretStoreTest {

    private InMemoryAgentSecretStore store;
    private AgentSecretCipher cipher;

    @BeforeEach
    void setUp() {
        cipher = new AgentSecretCipher(Base64.getEncoder().encodeToString(new byte[32]), "k1");
        store = new InMemoryAgentSecretStore(cipher);
    }

    @Test
    void roundTripAndSingleFulfill() {
        String id = store.createRequest("actor", "session", "draft",
                Instant.now().plusSeconds(600), 8);
        AgentSecretEnvelope envelope = cipher.encrypt("pw".toCharArray(),
                AgentSecretCipher.aad("actor", "session", id));
        assertTrue(store.fulfill(id, "actor", "session", envelope));
        assertFalse(store.fulfill(id, "actor", "session", envelope));
        Optional<char[]> resolved = store.resolve(id, "actor", "session", "probe", Instant.now());
        assertTrue(resolved.isPresent());
        assertArrayEquals("pw".toCharArray(), resolved.get());
        assertTrue(store.revoke(id, "actor", "session"));
        assertTrue(store.resolve(id, "actor", "session", "probe", Instant.now()).isEmpty());
    }

    @Test
    void readLimitApplies() {
        String id = store.createRequest("actor", "session", "draft",
                Instant.now().plusSeconds(600), 1);
        store.fulfill(id, "actor", "session",
                cipher.encrypt("x".toCharArray(), AgentSecretCipher.aad("actor", "session", id)));
        assertTrue(store.resolve(id, "actor", "session", "probe", Instant.now()).isPresent());
        assertTrue(store.resolve(id, "actor", "session", "probe", Instant.now()).isEmpty());
    }
}
