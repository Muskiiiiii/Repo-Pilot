package com.repopilot.testing.daemon.model;

public enum DaemonStatus {
    SUCCESS,
    UNAUTHENTICATED,
    UNAUTHORIZED,
    STALE_FENCE,
    LEASE_EXPIRED,
    UNKNOWN_JOB,
    UNKNOWN_EXECUTION,
    INVALID_REQUEST,
    CONFLICT,
    DAEMON_UNAVAILABLE,
    TIMEOUT
}
