package com.repopilot.llm.dto;

import java.time.Instant;
import java.util.UUID;

public class ProviderHealthResponse {

    private String providerId;
    private UUID organizationId;
    private String healthStatus;
    private Instant lastAttemptAt;
    private Instant lastSuccessfulAttemptAt;
    private long recentAttemptCount;
    private long recentSuccessCount;
    private long recentFailureCount;
    private long recentUnknownOutcomeCount;
    private long recentRateLimitedCount;
    private long recentReconciliationRequiredCount;
    private int observationWindowLimit;

    public ProviderHealthResponse() {
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public String getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getLastSuccessfulAttemptAt() {
        return lastSuccessfulAttemptAt;
    }

    public void setLastSuccessfulAttemptAt(Instant lastSuccessfulAttemptAt) {
        this.lastSuccessfulAttemptAt = lastSuccessfulAttemptAt;
    }

    public long getRecentAttemptCount() {
        return recentAttemptCount;
    }

    public void setRecentAttemptCount(long recentAttemptCount) {
        this.recentAttemptCount = recentAttemptCount;
    }

    public long getRecentSuccessCount() {
        return recentSuccessCount;
    }

    public void setRecentSuccessCount(long recentSuccessCount) {
        this.recentSuccessCount = recentSuccessCount;
    }

    public long getRecentFailureCount() {
        return recentFailureCount;
    }

    public void setRecentFailureCount(long recentFailureCount) {
        this.recentFailureCount = recentFailureCount;
    }

    public long getRecentUnknownOutcomeCount() {
        return recentUnknownOutcomeCount;
    }

    public void setRecentUnknownOutcomeCount(long recentUnknownOutcomeCount) {
        this.recentUnknownOutcomeCount = recentUnknownOutcomeCount;
    }

    public long getRecentRateLimitedCount() {
        return recentRateLimitedCount;
    }

    public void setRecentRateLimitedCount(long recentRateLimitedCount) {
        this.recentRateLimitedCount = recentRateLimitedCount;
    }

    public long getRecentReconciliationRequiredCount() {
        return recentReconciliationRequiredCount;
    }

    public void setRecentReconciliationRequiredCount(long recentReconciliationRequiredCount) {
        this.recentReconciliationRequiredCount = recentReconciliationRequiredCount;
    }

    public int getObservationWindowLimit() {
        return observationWindowLimit;
    }

    public void setObservationWindowLimit(int observationWindowLimit) {
        this.observationWindowLimit = observationWindowLimit;
    }
}
