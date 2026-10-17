package com.consilens.server.application.ai.plan;

/**
 * Shared lease window for agent-driven plan execution and task submission.
 * The lease is not renewed while an action runs, so both executors must use
 * the same duration and it must comfortably exceed one provisioning action or
 * task submission (database work plus remote calls).
 */
final class AgentSessionLease {

    static final long AGENT_SESSION_LEASE_SECONDS = 120;

    private AgentSessionLease() {
    }
}
