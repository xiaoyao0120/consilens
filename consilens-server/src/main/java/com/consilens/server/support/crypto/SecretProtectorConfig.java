package com.consilens.server.support.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the versioned SecretProtector. A missing key does NOT fall back to
 * plaintext: a failing protector is installed and every protect/reveal call
 * fails loudly. The AI startup validator additionally gates AI startup on the
 * key being present.
 */
@Configuration
public class SecretProtectorConfig {

    @Bean
    public SecretProtector secretProtector(
            @Value("${consilens.server.datasource.encryption-key:}") String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            return new MissingKeySecretProtector();
        }
        return new AesGcmSecretProtector(keyBase64, "k1");
    }

    static final class MissingKeySecretProtector implements SecretProtector {
        @Override
        public String protect(String plaintext) {
            throw new IllegalStateException(
                    "datasource encryption key is not configured; plaintext fallback is disabled");
        }

        @Override
        public String reveal(String envelope) {
            throw new IllegalStateException(
                    "datasource encryption key is not configured; plaintext fallback is disabled");
        }

        @Override
        public String keyId() {
            return "missing";
        }
    }
}
