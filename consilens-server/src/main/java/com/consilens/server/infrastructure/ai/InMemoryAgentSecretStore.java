package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentSecretEnvelope;
import com.consilens.agent.api.store.AgentSecretStore;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/test secret store with the same envelope semantics as the database
 * store: ciphertext is stored and decrypted on resolve with the bound AAD.
 * The startup validator refuses this store for production profiles.
 */
public final class InMemoryAgentSecretStore implements AgentSecretStore {

    private final AgentSecretCipher cipher;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public InMemoryAgentSecretStore(AgentSecretCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String createRequest(String actorId, String sessionId, String draftId,
                                Instant expiresAt, int maxReads) {
        String id = "sec_" + UUID.randomUUID();
        entries.put(id, new Entry(actorId, sessionId, draftId, "PENDING", null,
                0, maxReads, expiresAt, 0));
        return id;
    }

    @Override
    public boolean fulfill(String secretRequestId, String actorId, String sessionId,
                           AgentSecretEnvelope encryptedPayload) {
        Entry entry = entries.get(secretRequestId);
        if (entry == null || !"PENDING".equals(entry.status)
                || !entry.actorId.equals(actorId) || !entry.sessionId.equals(sessionId)) {
            return false;
        }
        entries.put(secretRequestId, entry.withStatus("PROVIDED", encryptedPayload));
        return true;
    }

    @Override
    public Optional<char[]> resolve(String secretRequestId, String actorId, String sessionId,
                                    String purpose, Instant now) {
        Entry entry = entries.get(secretRequestId);
        if (entry == null || !"PROVIDED".equals(entry.status)
                || !entry.actorId.equals(actorId) || !entry.sessionId.equals(sessionId)
                || (entry.expiresAt != null && !entry.expiresAt.isAfter(now))
                || entry.readCount >= entry.maxReads
                || entry.envelope == null) {
            return Optional.empty();
        }
        Entry next = entry.withReadCount(entry.readCount + 1);
        entries.put(secretRequestId, next);
        return Optional.of(cipher.decrypt(entry.envelope,
                AgentSecretCipher.aad(actorId, sessionId, secretRequestId)));
    }

    @Override
    public Optional<Instant> expiresAt(String secretRequestId, String actorId, String sessionId) {
        Entry entry = entries.get(secretRequestId);
        if (entry == null || !entry.actorId.equals(actorId) || !entry.sessionId.equals(sessionId)
                || entry.expiresAt == null) {
            return Optional.empty();
        }
        return Optional.of(entry.expiresAt);
    }

    @Override
    public boolean consume(String secretRequestId, String actorId, String sessionId) {
        return transition(secretRequestId, actorId, sessionId, "CONSUMED");
    }

    @Override
    public boolean revoke(String secretRequestId, String actorId, String sessionId) {
        return transition(secretRequestId, actorId, sessionId, "REVOKED");
    }

    private boolean transition(String id, String actorId, String sessionId, String status) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.actorId.equals(actorId) || !entry.sessionId.equals(sessionId)) {
            return false;
        }
        entries.put(id, entry.withStatus(status, null));
        return true;
    }

    private static final class Entry {
        final String actorId;
        final String sessionId;
        final String draftId;
        final String status;
        final AgentSecretEnvelope envelope;
        final int readCount;
        final int maxReads;
        final Instant expiresAt;
        final long version;

        Entry(String actorId, String sessionId, String draftId, String status,
              AgentSecretEnvelope envelope, int readCount, int maxReads, Instant expiresAt, long version) {
            this.actorId = actorId;
            this.sessionId = sessionId;
            this.draftId = draftId;
            this.status = status;
            this.envelope = envelope;
            this.readCount = readCount;
            this.maxReads = maxReads;
            this.expiresAt = expiresAt;
            this.version = version;
        }

        Entry withStatus(String status, AgentSecretEnvelope envelope) {
            return new Entry(actorId, sessionId, draftId, status, envelope,
                    readCount, maxReads, expiresAt, version + 1);
        }

        Entry withReadCount(int count) {
            return new Entry(actorId, sessionId, draftId, status, envelope,
                    count, maxReads, expiresAt, version + 1);
        }
    }
}
