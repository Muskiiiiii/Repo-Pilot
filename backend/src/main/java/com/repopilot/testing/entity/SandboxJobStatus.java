package com.repopilot.testing.entity;

/**
 * Lifecycle state model for Phase 14 Ephemeral Sandbox Execution Jobs.
 */
public enum SandboxJobStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    LEASE_EXPIRED
}
