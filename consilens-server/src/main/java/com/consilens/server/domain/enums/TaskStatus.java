package com.consilens.server.domain.enums;

public enum TaskStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RETRYABLE,
    CANCELLED
}
