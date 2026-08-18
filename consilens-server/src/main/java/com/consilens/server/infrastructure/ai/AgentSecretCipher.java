package com.consilens.server.infrastructure.ai;

import com.consilens.agent.api.store.AgentSecretEnvelope;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM secret cipher with AAD bound to actor/session/request
 * (design section 23.5). Envelope fields are stored separately; a plaintext
 * value never leaves the resolve call.
 */
public final class AgentSecretCipher {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final String keyId;

    /**
     * @param keyBase64 Base64 32-byte key, or blank/null for fail-loud mode
     *                  (construction succeeds, every crypto call throws).
     */
    public AgentSecretCipher(String keyBase64, String keyId) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            this.key = null;
            this.keyId = keyId == null ? "missing" : keyId;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("agent secret key must be Base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException("agent secret key must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        this.keyId = keyId;
    }

    public AgentSecretEnvelope encrypt(char[] plaintext, String aad) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aadBytes(aad));
            byte[] full = cipher.doFinal(toBytes(plaintext));
            byte[] tag = Arrays.copyOfRange(full, full.length - TAG_LENGTH, full.length);
            byte[] body = Arrays.copyOfRange(full, 0, full.length - TAG_LENGTH);
            return AgentSecretEnvelope.builder()
                    .cipherAlgorithm("AES-256-GCM")
                    .keyId(keyId)
                    .nonce(nonce)
                    .ciphertext(body)
                    .tag(tag)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to encrypt agent secret", e);
        }
    }

    public char[] decrypt(AgentSecretEnvelope envelope, String aad) {
        requireKey();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, envelope.getNonce()));
            cipher.updateAAD(aadBytes(aad));
            byte[] full = Arrays.copyOf(envelope.getCiphertext(),
                    envelope.getCiphertext().length + envelope.getTag().length);
            System.arraycopy(envelope.getTag(), 0, full, envelope.getCiphertext().length,
                    envelope.getTag().length);
            return toChars(cipher.doFinal(full));
        } catch (Exception e) {
            throw new IllegalArgumentException("agent secret decryption failed (AAD mismatch or tampering)", e);
        }
    }

    public static String aad(String actorId, String sessionId, String secretRequestId) {
        return actorId + "|" + sessionId + "|" + secretRequestId;
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "agent secret key is not configured; refusing plaintext fallback");
        }
    }

    private static byte[] aadBytes(String aad) {
        return (aad == null ? "" : aad).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] toBytes(char[] value) {
        return new String(value).getBytes(StandardCharsets.UTF_8);
    }

    private static char[] toChars(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).toCharArray();
    }
}
