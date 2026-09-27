package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmProviderAttempt;

import java.time.Instant;
import java.util.UUID;

public class LlmExecutionClaimResult {

    private final boolean success;
    private final String rejectionReason;
    private final UUID attemptId;
    private final String leaseToken;
    private final Long fencingVersion;
    private final Instant claimExpiresAt;
    private final LlmProviderAttempt attempt;

    private LlmExecutionClaimResult(boolean success,
                                    String rejectionReason,
                                    UUID attemptId,
                                    String leaseToken,
                                    Long fencingVersion,
                                    Instant claimExpiresAt,
                                    LlmProviderAttempt attempt) {
        this.success = success;
        this.rejectionReason = rejectionReason;
        this.attemptId = attemptId;
        this.leaseToken = leaseToken;
        this.fencingVersion = fencingVersion;
        this.claimExpiresAt = claimExpiresAt;
        this.attempt = attempt;
    }

    public static LlmExecutionClaimResult success(UUID attemptId,
                                                 String leaseToken,
                                                 Long fencingVersion,
                                                 Instant claimExpiresAt,
                                                 LlmProviderAttempt attempt) {
        return new LlmExecutionClaimResult(true, null, attemptId, leaseToken, fencingVersion, claimExpiresAt, attempt);
    }

    public static LlmExecutionClaimResult rejected(String rejectionReason) {
        return new LlmExecutionClaimResult(false, rejectionReason, null, null, null, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public LlmProviderAttempt getAttempt() {
        return attempt;
    }

    @Override
    public String toString() {
        // Redact lease token from toString() to prevent secret leakage in logs
        return "LlmExecutionClaimResult{" +
                "success=" + success +
                ", rejectionReason='" + rejectionReason + '\'' +
                ", attemptId=" + attemptId +
                ", leaseToken='[REDACTED]'" +
                ", fencingVersion=" + fencingVersion +
                '}';
    }
}
