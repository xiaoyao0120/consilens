package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentSecretEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSecretCipherTest {

    private final AgentSecretCipher cipher =
            new AgentSecretCipher(Base64.getEncoder().encodeToString(new byte[32]), "k1");

    @Test
    void roundTripsWithMatchingAad() {
        AgentSecretEnvelope envelope = cipher.encrypt("s3cret".toCharArray(),
                AgentSecretCipher.aad("actor", "session", "request"));
        char[] plain = cipher.decrypt(envelope, AgentSecretCipher.aad("actor", "session", "request"));
        assertArrayEquals("s3cret".toCharArray(), plain);
    }

    @Test
    void wrongAadFailsDecryption() {
        AgentSecretEnvelope envelope = cipher.encrypt("secret".toCharArray(),
                AgentSecretCipher.aad("actor", "session", "request"));
        assertThrows(IllegalArgumentException.class,
                () -> cipher.decrypt(envelope, AgentSecretCipher.aad("other", "session", "request")));
    }

    @Test
    void nonceIsFreshPerEncryption() {
        char[] value = "same".toCharArray();
        AgentSecretEnvelope first = cipher.encrypt(value, "aad");
        AgentSecretEnvelope second = cipher.encrypt(value, "aad");
        assertNotEquals(java.util.Arrays.toString(first.getNonce()),
                java.util.Arrays.toString(second.getNonce()));
    }

    @Test
    void missingKeyFailsLoudly() {
        AgentSecretCipher missing = new AgentSecretCipher("", "k1");
        assertThrows(IllegalStateException.class,
                () -> missing.encrypt("x".toCharArray(), "aad"));
    }

    @Test
    void shortKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSecretCipher(Base64.getEncoder().encodeToString(new byte[16]), "k1"));
    }

    @Test
    void envelopeCarriesAlgorithmAndKeyId() {
        AgentSecretEnvelope envelope = cipher.encrypt("x".toCharArray(), "aad");
        assertTrue(envelope.getCipherAlgorithm().contains("GCM"));
        assertTrue(envelope.getKeyId().equals("k1"));
        assertTrue(envelope.getCiphertext().length > 0);
        assertTrue(envelope.getTag().length == 16);
    }

    @Test
    void roundTripsNonAsciiPasswords() {
        AgentSecretEnvelope envelope = cipher.encrypt("密码123".toCharArray(),
                AgentSecretCipher.aad("actor", "session", "request"));
        char[] plain = cipher.decrypt(envelope, AgentSecretCipher.aad("actor", "session", "request"));
        assertArrayEquals("密码123".toCharArray(), plain);
    }
}
