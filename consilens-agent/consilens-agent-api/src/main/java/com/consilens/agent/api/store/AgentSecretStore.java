package com.consilens.agent.api.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Out-of-band secret storage. Production uses an AES-GCM envelope store with
 * AAD bound to actor/session/request; memory stores are only allowed in
 * non-production profiles.
 */
public interface AgentSecretStore {

    String createRequest(String actorId, String sessionId, String draftId, Instant expiresAt, int maxReads);

    /**
     * CAS from PENDING to PROVIDED bound to actor/session; only the first
     * submission can win.
     */
    boolean fulfill(String secretRequestId, String actorId, String sessionId,
                    AgentSecretEnvelope encryptedPayload);

    Optional<char[]> resolve(String secretRequestId, String actorId, String sessionId,
                             String purpose, Instant now);

    /** Expiry of a still-valid request, for UI display consistency. */
    Optional<Instant> expiresAt(String secretRequestId, String actorId, String sessionId);

    boolean consume(String secretRequestId, String actorId, String sessionId);

    boolean revoke(String secretRequestId, String actorId, String sessionId);
}
