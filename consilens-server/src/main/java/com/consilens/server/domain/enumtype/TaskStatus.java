package com.consilens.server.domain.enumtype;

public enum TaskStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    RETRYABLE,
    CANCELLED
}
