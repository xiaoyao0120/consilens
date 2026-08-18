package com.consilens.agent.api.tool;

/**
 * Out-of-band secret access for server-side tool executors. Secrets are never
 * part of working state, model requests or events.
 */
public interface AgentSecretResolver {

    char[] resolve(String secretRequestId);

    void consume(String secretRequestId);
}
