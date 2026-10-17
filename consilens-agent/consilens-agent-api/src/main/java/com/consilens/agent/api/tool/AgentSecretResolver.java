package com.consilens.agent.api.tool;

/**
 * Out-of-band secret access for server-side tool executors. Secrets are never
 * part of working state, model requests or events.
 */
public interface AgentSecretResolver {

    /**
     * Default resolver used when no out-of-band secret access is configured.
     * Suitable for tests and for runs whose tools never need secrets.
     */
    AgentSecretResolver NOOP = new AgentSecretResolver() {
        @Override
        public char[] resolve(String secretRequestId) {
            return new char[0];
        }

        @Override
        public void consume(String secretRequestId) {
            // no-op
        }
    };

    char[] resolve(String secretRequestId);

    void consume(String secretRequestId);
}
