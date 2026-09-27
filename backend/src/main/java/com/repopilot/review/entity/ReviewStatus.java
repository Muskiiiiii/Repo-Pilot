package com.repopilot.review.entity;

public enum ReviewStatus {
    PENDING,
    RUNNING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    STALE,
    STALE_REVIEW,
    REJECTED_CONTEXT_TOO_LARGE,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == STALE || this == STALE_REVIEW || this == REJECTED_CONTEXT_TOO_LARGE || this == CANCELLED;
    }
}
