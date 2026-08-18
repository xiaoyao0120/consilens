package com.consilens.agent.api.store;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;

/**
 * Encrypted payload stored by the secret store. Sensitive fields are excluded
 * from {@code toString} and must never reach logs, events or model requests.
 */
@Value
@Builder
public class AgentSecretEnvelope {
    String cipherAlgorithm;
    String keyId;
    @ToString.Exclude
    byte[] nonce;
    @ToString.Exclude
    byte[] ciphertext;
    @ToString.Exclude
    byte[] tag;
}
