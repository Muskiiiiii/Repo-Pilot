package com.repopilot.testing.entity;

public enum TerminationReason {
    NORMAL_EXIT,
    TIMEOUT,
    OOM,
    PID_LIMIT,
    RESOURCE_LIMIT,
    CANCELLED,
    EXECUTION_INFRA_FAILURE
}
