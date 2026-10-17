package com.consilens.server.infrastructure.ai;

import com.consilens.server.infrastructure.db.mapper.ai.AiSecretMapper;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Periodically expires stale secret requests and clears their ciphertext so
 * the database does not retain resolvable secrets beyond their TTL.
 */
public class AgentSecretCleanupScheduler {

    private final AiSecretMapper secretMapper;

    public AgentSecretCleanupScheduler(AiSecretMapper secretMapper) {
        this.secretMapper = secretMapper;
    }

    @Scheduled(fixedDelayString = "${consilens.server.ai.secret-cleanup-interval-ms:600000}")
    public int expireStaleSecrets() {
        return secretMapper.expireStale(LocalDateTime.now(ZoneOffset.UTC));
    }
}
