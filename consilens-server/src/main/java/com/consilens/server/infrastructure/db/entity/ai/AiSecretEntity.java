package com.consilens.server.infrastructure.db.entity.ai;

import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * cs_ai_secret: encrypted out-of-band secrets. Ciphertext fields are excluded
 * from toString so accidental logging never exposes secret material.
 */
@Data
@ToString(exclude = {"nonce", "ciphertext", "tag"})
public class AiSecretEntity {
    private String secretRequestId;
    private String actorId;
    private String sessionId;
    private String draftId;
    private String status;
    private String cipherAlgorithm;
    private String keyId;
    private byte[] nonce;
    private byte[] ciphertext;
    private byte[] tag;
    private Integer readCount;
    private Integer maxReads;
    private LocalDateTime expiresAt;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime consumedAt;
}
