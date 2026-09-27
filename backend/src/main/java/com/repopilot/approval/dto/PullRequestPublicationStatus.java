package com.repopilot.approval.dto;

public enum PullRequestPublicationStatus {
    PENDING,
    VERIFYING,
    PUSHING,
    PUSHED,
    RECONCILED,
    PR_CREATING,
    PR_CREATED,
    PR_RECONCILED,
    FAILED_STALE,
    FAILED_CONFLICT,
    FAILED_PUSH,
    FAILED_VERIFICATION,
    FAILED_PR_CREATION,
    FAILED_PR_CONFLICT,
    FAILED_PR_VERIFICATION;

    public boolean isPushed() {
        return this == PUSHED || this == RECONCILED || this == PR_CREATING || this == PR_CREATED || this == PR_RECONCILED || this == FAILED_PR_CREATION;
    }

    public boolean isPrCreated() {
        return this == PR_CREATED || this == PR_RECONCILED;
    }

    public boolean isTerminal() {
        return this == PUSHED || this == RECONCILED || this == PR_CREATED || this == PR_RECONCILED ||
               this == FAILED_STALE || this == FAILED_CONFLICT || this == FAILED_PUSH ||
               this == FAILED_VERIFICATION || this == FAILED_PR_CREATION ||
               this == FAILED_PR_CONFLICT || this == FAILED_PR_VERIFICATION;
    }
}
