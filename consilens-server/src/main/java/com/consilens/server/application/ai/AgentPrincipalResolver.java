package com.consilens.server.application.ai;

import com.consilens.agent.api.policy.AgentActor;
import com.consilens.server.boot.ConsilensServerProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * First-version actor identity: fingerprint of the global API key, or "local"
 * when security is disabled. All sessions/resources/approvals bind to this
 * actor id; a real identity system can replace it without schema migration.
 */
@Component
public class AgentPrincipalResolver {

    private final ConsilensServerProperties properties;

    public AgentPrincipalResolver(ConsilensServerProperties properties) {
        this.properties = properties;
    }

    public AgentActor resolve() {
        String apiKey = properties.getSecurity().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return AgentActor.builder().id("local").displayName("local").build();
        }
        return AgentActor.builder().id(fingerprint(apiKey)).displayName("api-key").build();
    }

    private static String fingerprint(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
