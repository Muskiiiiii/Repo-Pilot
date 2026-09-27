package com.repopilot.task.entity;

public enum ExecutionStatus {
    CREATED,
    PREPARING,
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    CLEANUP_PENDING,
    CLEANED,
    ERROR
}
