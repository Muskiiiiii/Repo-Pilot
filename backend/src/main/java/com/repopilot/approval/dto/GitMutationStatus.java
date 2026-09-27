package com.repopilot.approval.dto;

public enum GitMutationStatus {
    COMMITTED,
    VERIFICATION_FAILED,
    APPROVAL_STALE,
    BASE_SHA_MISMATCH,
    PATH_VIOLATION,
    GIT_EXECUTION_FAILED,
    INDEX_MISMATCH,
    COMMIT_VERIFICATION_FAILED,
    MASTER_MUTATED;

    public boolean isCommitted() {
        return this == COMMITTED;
    }
}
