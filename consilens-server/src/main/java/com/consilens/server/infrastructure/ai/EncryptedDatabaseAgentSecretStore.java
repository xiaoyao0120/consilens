package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentSecretEnvelope;
import com.consilens.agent.api.store.AgentSecretStore;
import com.consilens.server.infrastructure.db.entity.ai.AiSecretEntity;
import com.consilens.server.infrastructure.db.mapper.ai.AiSecretMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Production secret store: AES-GCM envelope in {@code cs_ai_secret} with AAD
 * bound to actor/session/request. Fulfillment is CAS-once; reads are limited
 * by TTL and max_reads; consume/revoke clear the ciphertext.
 */
public class EncryptedDatabaseAgentSecretStore implements AgentSecretStore {

    private final AiSecretMapper secretMapper;
    private final AgentSecretCipher cipher;

    public EncryptedDatabaseAgentSecretStore(AiSecretMapper secretMapper, AgentSecretCipher cipher) {
        this.secretMapper = secretMapper;
        this.cipher = cipher;
    }

    @Override
    public String createRequest(String actorId, String sessionId, String draftId,
                                Instant expiresAt, int maxReads) {
        String requestId = "sec_" + UUID.randomUUID();
        AiSecretEntity entity = new AiSecretEntity();
        entity.setSecretRequestId(requestId);
        entity.setActorId(actorId);
        entity.setSessionId(sessionId);
        entity.setDraftId(draftId);
        entity.setStatus("PENDING");
        entity.setReadCount(0);
        entity.setMaxReads(maxReads);
        entity.setExpiresAt(toLocal(expiresAt));
        entity.setVersion(0L);
        entity.setCreatedAt(toLocal(Instant.now()));
        secretMapper.insert(entity);
        return requestId;
    }

    @Override
    public boolean fulfill(String secretRequestId, String actorId, String sessionId,
                           AgentSecretEnvelope encryptedPayload) {
        int rows = secretMapper.fulfill(secretRequestId, actorId, sessionId, toLocal(Instant.now()),
                encryptedPayload.getCipherAlgorithm(),
                encryptedPayload.getKeyId(),
                encryptedPayload.getNonce(),
                encryptedPayload.getCiphertext(),
                encryptedPayload.getTag());
        return rows == 1;
    }

    @Override
    public Optional<char[]> resolve(String secretRequestId, String actorId, String sessionId,
                                    String purpose, Instant now) {
        AiSecretEntity entity = secretMapper.findById(secretRequestId);
        if (entity == null
                || !entity.getActorId().equals(actorId)
                || !entity.getSessionId().equals(sessionId)
                || !"PROVIDED".equals(entity.getStatus())
                || (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(toLocal(now)))
                || entity.getReadCount() >= entity.getMaxReads()) {
            return Optional.empty();
        }
        int read = secretMapper.incrementRead(secretRequestId, actorId, sessionId, toLocal(now));
        if (read != 1) {
            return Optional.empty();
        }
        AgentSecretEnvelope envelope = AgentSecretEnvelope.builder()
                .cipherAlgorithm(entity.getCipherAlgorithm())
                .keyId(entity.getKeyId())
                .nonce(entity.getNonce())
                .ciphertext(entity.getCiphertext())
                .tag(entity.getTag())
                .build();
        return Optional.of(cipher.decrypt(envelope, AgentSecretCipher.aad(actorId, sessionId, secretRequestId)));
    }

    @Override
    public Optional<Instant> expiresAt(String secretRequestId, String actorId, String sessionId) {
        AiSecretEntity entity = secretMapper.findById(secretRequestId);
        if (entity == null || !entity.getActorId().equals(actorId)
                || !entity.getSessionId().equals(sessionId)
                || entity.getExpiresAt() == null) {
            return Optional.empty();
        }
        return Optional.of(entity.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Override
    public boolean consume(String secretRequestId, String actorId, String sessionId) {
        return secretMapper.consume(secretRequestId, actorId, sessionId, toLocal(Instant.now())) == 1;
    }

    @Override
    public boolean revoke(String secretRequestId, String actorId, String sessionId) {
        return secretMapper.revoke(secretRequestId, actorId, sessionId, toLocal(Instant.now())) == 1;
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
