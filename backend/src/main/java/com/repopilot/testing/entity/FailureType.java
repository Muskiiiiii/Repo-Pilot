package com.repopilot.testing.entity;

public enum FailureType {
    PASS,
    CODE_FAILURE,
    TEST_FAILURE,
    COMPILATION_FAILURE,
    TIMEOUT,
    OOM,
    RESOURCE_LIMIT,
    DEPENDENCY_UNAVAILABLE,
    NETWORK_REQUIRED,
    EXECUTION_INFRA_FAILURE,
    CANCELLED,
    UNKNOWN
}
