CREATE TABLE IF NOT EXISTS cs_server_node (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_key VARCHAR(128) NOT NULL,
    host VARCHAR(64) NOT NULL,
    port INT NOT NULL,
    machine_code VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    load_average DOUBLE,
    available_memory_mb DOUBLE,
    heartbeat_time DATETIME(3) NOT NULL,
    status_update_time DATETIME(3),
    status_update_by VARCHAR(128),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cs_server_node_node_key (node_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_task_instance (
    id BIGINT NOT NULL AUTO_INCREMENT,
    instance_key VARCHAR(64) NOT NULL,
    definition_id BIGINT,
    serial_no VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(64),
    trace_id VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(64),
    request_payload MEDIUMTEXT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    priority INT NOT NULL DEFAULT 5,
    schedule_time DATETIME(3) NOT NULL,
    submit_time DATETIME(3) NOT NULL,
    start_time DATETIME(3),
    end_time DATETIME(3),
    execute_node_key VARCHAR(128),
    result_artifact_id VARCHAR(64),
    error_code VARCHAR(64),
    error_message MEDIUMTEXT,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 3,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cs_task_instance_key (instance_key),
    UNIQUE KEY uk_cs_task_serial_no (serial_no),
    KEY idx_cs_task_status_schedule (status, schedule_time),
    KEY idx_cs_task_execute_node_status (execute_node_key, status),
    KEY idx_cs_task_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_task_command (
    id BIGINT NOT NULL AUTO_INCREMENT,
    command_key VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    shard_key VARCHAR(64) NOT NULL,
    shard_slot INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    execute_node_key VARCHAR(128),
    lock_time DATETIME(3),
    lock_until DATETIME(3),
    schedule_time DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cs_task_command_command_key (command_key),
    KEY idx_cs_task_command_shard_status_schedule (shard_slot, status, schedule_time),
    KEY idx_cs_task_command_execute_node_status (execute_node_key, status),
    KEY idx_cs_task_command_task_id (task_id),
    CONSTRAINT fk_cs_task_command_task_id FOREIGN KEY (task_id) REFERENCES cs_task_instance (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_artifact (
    id VARCHAR(64) NOT NULL,
    task_id BIGINT,
    trace_id VARCHAR(128) NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    artifact_format VARCHAR(16) NOT NULL,
    storage_type VARCHAR(16) NOT NULL,
    storage_uri VARCHAR(512) NOT NULL,
    sha256 VARCHAR(64),
    metadata_json MEDIUMTEXT,
    statistics_json MEDIUMTEXT,
    difference_count BIGINT,
    difference_truncated BOOLEAN,
    difference_rows INT,
    differences_uri VARCHAR(512),
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_cs_artifact_task_id (task_id),
    KEY idx_cs_artifact_trace_id (trace_id),
    KEY idx_cs_artifact_type_created_at (artifact_type, created_at),
    CONSTRAINT fk_cs_artifact_task_id FOREIGN KEY (task_id) REFERENCES cs_task_instance (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_datasource (
    id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(64) NOT NULL,
    param MEDIUMTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cs_datasource_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_task_definition (
    id BIGINT NOT NULL,
    definition_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    task_type VARCHAR(32) NOT NULL DEFAULT 'RUN',
    config MEDIUMTEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    schedule_type VARCHAR(16),
    cron_expr VARCHAR(64),
    last_run_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cs_task_definition_key (definition_key),
    UNIQUE KEY uk_cs_task_definition_name (name),
    KEY idx_cs_task_definition_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_cs_task_instance_definition ON cs_task_instance (definition_id, submit_time);

-- ==========================================================================
-- AI Agent durable session/event/plan/secret tables (design section 26.1)
-- ==========================================================================

CREATE TABLE IF NOT EXISTS cs_ai_session (
    id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(64),
    title VARCHAR(128),
    objective VARCHAR(1024),
    status VARCHAR(32) NOT NULL,
    workflow_stage VARCHAR(32) NOT NULL,
    active_run_id VARCHAR(64),
    next_seq BIGINT NOT NULL DEFAULT 0,
    snapshot_seq BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_expires_at DATETIME(3),
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_cs_ai_session_actor_updated (actor_id, updated_at),
    KEY idx_cs_ai_session_status_lease (status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    run_id VARCHAR(64),
    turn_id VARCHAR(64),
    event_type VARCHAR(48) NOT NULL,
    visibility VARCHAR(24) NOT NULL,
    schema_version INT NOT NULL,
    payload MEDIUMTEXT,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cs_ai_event_session_seq (session_id, seq),
    KEY idx_cs_ai_event_session_run_seq (session_id, run_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_run (
    run_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    resume_from_run_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(128),
    lease_expires_at DATETIME(3),
    turn_count INT NOT NULL DEFAULT 0,
    tool_call_count INT NOT NULL DEFAULT 0,
    token_count BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    started_at DATETIME(3),
    ended_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id),
    UNIQUE KEY uk_cs_ai_run_session_request (session_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_tool_call (
    call_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64),
    source_order INT NOT NULL,
    tool_name VARCHAR(96) NOT NULL,
    status VARCHAR(24) NOT NULL,
    risk_level VARCHAR(16),
    redacted_args MEDIUMTEXT,
    args_digest VARCHAR(64),
    idempotency_key VARCHAR(64),
    action_digest VARCHAR(64),
    result_event_seq BIGINT,
    error_code VARCHAR(64),
    retryable BOOLEAN,
    result_summary VARCHAR(1024),
    started_at DATETIME(3),
    ended_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (call_id),
    KEY idx_cs_ai_tool_call_session_idem (session_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_approval (
    approval_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    proposed_run_id VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    action_digest VARCHAR(64) NOT NULL,
    safe_summary VARCHAR(1024),
    safe_actions_json MEDIUMTEXT,
    actor_id VARCHAR(128),
    decided_at DATETIME(3),
    expires_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (approval_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_plan (
    plan_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    objective_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    plan_digest VARCHAR(64) NOT NULL,
    config_template_digest VARCHAR(64),
    safe_summary VARCHAR(1024),
    plan_json MEDIUMTEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_plan_action (
    plan_id VARCHAR(64) NOT NULL,
    action_id VARCHAR(64) NOT NULL,
    sequence INT NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    name VARCHAR(160),
    safe_args_json MEDIUMTEXT,
    status VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(64),
    input_digest VARCHAR(64),
    resource_type VARCHAR(32),
    resource_id VARCHAR(64),
    result_digest VARCHAR(64),
    error_code VARCHAR(64),
    retryable BOOLEAN,
    started_at DATETIME(3),
    ended_at DATETIME(3),
    PRIMARY KEY (plan_id, action_id),
    UNIQUE KEY uk_cs_ai_plan_action_seq (plan_id, sequence),
    UNIQUE KEY uk_cs_ai_plan_action_idem (plan_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_secret (
    secret_request_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    draft_id VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    cipher_algorithm VARCHAR(32),
    key_id VARCHAR(64),
    nonce VARBINARY(64),
    ciphertext VARBINARY(2048),
    tag VARBINARY(64),
    read_count INT NOT NULL DEFAULT 0,
    max_reads INT NOT NULL DEFAULT 8,
    expires_at DATETIME(3),
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3),
    PRIMARY KEY (secret_request_id),
    KEY idx_cs_ai_secret_session_status (session_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS cs_ai_snapshot (
    session_id VARCHAR(64) NOT NULL,
    seq BIGINT NOT NULL,
    working_state MEDIUMTEXT NOT NULL,
    conversation_summary MEDIUMTEXT,
    schema_version INT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
