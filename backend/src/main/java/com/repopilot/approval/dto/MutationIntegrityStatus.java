package com.repopilot.approval.dto;

public enum MutationIntegrityStatus {
    VERIFIED,
    INTEGRITY_FAILED,
    APPROVAL_STALE,
    SNAPSHOT_MISMATCH,
    BASELINE_MUTATED,
    UNEXPECTED_FILE,
    PATH_VIOLATION,
    SECRET_DETECTED,
    WORKSPACE_UNSTABLE
}
