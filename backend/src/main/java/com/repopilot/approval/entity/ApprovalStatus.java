package com.repopilot.approval.entity;

public enum ApprovalStatus {
    PENDING_APPROVAL,
    APPROVED,
    APPROVAL_STALE,
    REJECTED,
    REVOKED,
    CANCELLED,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == APPROVED || this == APPROVAL_STALE || this == REJECTED || this == REVOKED || this == CANCELLED || this == COMPLETED || this == FAILED;
    }

    public boolean isApproved() {
        return this == APPROVED || this == PROCESSING || this == COMPLETED;
    }

    public boolean isStale() {
        return this == APPROVAL_STALE;
    }
}
