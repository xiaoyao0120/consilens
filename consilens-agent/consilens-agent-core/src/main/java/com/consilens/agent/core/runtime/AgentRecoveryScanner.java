package com.consilens.agent.core.runtime;

/**
 * Scans for expired leases and INTERRUPTED runs, marks them recoverable and
 * re-queues them (section 25.4). Recovery must verify persisted resource refs
 * before retrying any non-idempotent write.
 */
public interface AgentRecoveryScanner {

    int scanAndRecover();
}
