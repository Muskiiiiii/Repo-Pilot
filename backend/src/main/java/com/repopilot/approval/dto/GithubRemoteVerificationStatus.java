package com.repopilot.approval.dto;

public enum GithubRemoteVerificationStatus {
    VERIFIED,
    REPOSITORY_NOT_FOUND,
    GITHUB_REPOSITORY_MISMATCH,
    REPOSITORY_MOVED,
    DEFAULT_BRANCH_NOT_FOUND,
    BASE_BRANCH_MOVED,
    APPROVAL_STALE,
    UNAUTHORIZED,
    GITHUB_AUTH_FAILURE,
    GITHUB_FORBIDDEN,
    GITHUB_RATE_LIMITED,
    GITHUB_NETWORK_FAILURE,
    GITHUB_API_FAILURE,
    INVALID_GITHUB_RESPONSE,
    SSRF_BLOCKED;

    public boolean isVerified() {
        return this == VERIFIED;
    }
}
