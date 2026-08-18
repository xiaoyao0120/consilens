package com.consilens.agent.api;

/**
 * Stable, machine-readable error codes shared by loop, tools, API and UI.
 * Never embed a raw exception message here; map it to a safe message at the boundary.
 */
public enum AgentErrorCode {

    // Session / run lifecycle
    SESSION_NOT_FOUND(false),
    SESSION_BUSY(false),
    SESSION_VERSION_CONFLICT(false),
    RUN_NOT_FOUND(false),
    RUN_NOT_RETRYABLE(false),
    REQUEST_ALREADY_PROCESSED(false),

    // Model client
    MODEL_CONFIG_INVALID(false),
    MODEL_PROVIDER_UNAVAILABLE(false),
    MODEL_RATE_LIMITED(true),
    MODEL_TRANSIENT_ERROR(true),
    MODEL_TIMEOUT(true),
    MODEL_CANCELLED(false),
    MODEL_TRUNCATED_ARGUMENTS(false),

    // Tool preflight / execution
    UNKNOWN_TOOL(false),
    TOOL_ARGUMENT_INVALID(false),
    TOOL_NOT_ALLOWED_IN_STAGE(false),
    TOOL_PERMISSION_DENIED(false),
    SECRET_IN_MODEL_ARGUMENTS(false),
    DEPENDENCY_NOT_READY(false),
    TOOL_EXECUTION_FAILED(true),
    TOOL_TIMEOUT(true),

    // Secrets
    SECRET_REQUEST_NOT_FOUND(false),
    SECRET_REQUEST_EXPIRED(false),
    SECRET_REQUEST_ALREADY_FULFILLED(false),
    SECRET_DECRYPTION_FAILED(false),
    SECRET_STORE_KEY_UNAVAILABLE(false),

    // Approval
    APPROVAL_NOT_FOUND(false),
    APPROVAL_STALE(false),
    APPROVAL_EXPIRED(false),
    APPROVAL_ALREADY_DECIDED(false),

    // Domain
    NO_DATASOURCE_TYPE_AVAILABLE(false),
    DATASOURCE_NOT_FOUND(false),
    DATASOURCE_NAME_CONFLICT(false),
    DATASOURCE_PROBE_FAILED(true),
    DATASOURCE_PROBE_AUTH_FAILED(false),
    DATASOURCE_PROBE_UNSUPPORTED(false),
    METADATA_READ_FAILED(true),
    TASK_DEFINITION_NAME_CONFLICT(false),
    CONFIG_VALIDATION_FAILED(false),
    PLAN_NOT_READY(false),
    PLAN_DIGEST_MISMATCH(false),

    // Budget / guard rails
    AGENT_BUDGET_EXHAUSTED(true),
    RUN_TIMEOUT(true),
    RUN_CANCELLED(false),

    // Internal
    INTERNAL_ERROR(true),
    STATE_INVARIANT_VIOLATION(false),
    CONCURRENT_MODIFICATION(false);

    private final boolean retryable;

    AgentErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
