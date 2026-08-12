package com.consilens.server.support.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Lightweight AES-GCM protector for datasource passwords.
 *
 * <p>The key is a Base64-encoded 32-byte secret from
 * {@code consilens.datasource.encryption-key}. When the key is not configured
 * (local development), passwords are stored in plaintext and a startup warning
 * is emitted.
 */
@Component
public class CryptoSupport {

    private static final Logger log = LoggerFactory.getLogger(CryptoSupport.class);
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String ENCRYPTED_PREFIX = "enc:";

    /** null when encryption is not configured (plaintext mode). */
    private final SecretKeySpec key;

    public CryptoSupport(@Value("${consilens.datasource.encryption-key:}") String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            this.key = null;
            log.warn("consilens.datasource.encryption-key is not configured; "
                    + "datasource passwords will be stored in plaintext");
        } else {
            this.key = new SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES");
        }
    }

    /**
     * Returns the value unchanged in plaintext mode; otherwise AES-GCM-encrypts
     * it with an "enc:" prefix. Note: "enc:" is a reserved prefix; a literal
     * password starting with it cannot be stored encrypted.
     */
    public String protect(String plain) {
        if (plain == null || plain.isEmpty() || key == null) {
            return plain;
        }
        if (plain.startsWith(ENCRYPTED_PREFIX)) {
            // defensive: values already carrying the prefix are not re-encrypted;
            // note: a literal password starting with "enc:" cannot be stored encrypted (reserved prefix)
            log.debug("password already carries reserved prefix '{}'; stored as-is", ENCRYPTED_PREFIX);
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv).put(cipherText);
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception exception) {
            throw new IllegalStateException("failed to encrypt datasource password");
        }
    }

    /**
     * Inverse of {@link #protect}. Values without the "enc:" prefix (legacy
     * plaintext) are returned unchanged; values that fail to decrypt are
     * treated as absent (null) with a warning so operations can still proceed.
     */
    public String reveal(String cipher) {
        if (cipher == null || cipher.isEmpty() || key == null) {
            return cipher;
        }
        if (!cipher.startsWith(ENCRYPTED_PREFIX)) {
            return cipher;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipher.substring(ENCRYPTED_PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            Cipher cipherInstance = Cipher.getInstance("AES/GCM/NoPadding");
            cipherInstance.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipherInstance.doFinal(all, IV_LENGTH, all.length - IV_LENGTH),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.warn("failed to decrypt datasource password; treating it as absent");
            return null;
        }
    }
}
