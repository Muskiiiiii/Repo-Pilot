package com.repopilot.llm.dto;

import java.time.Instant;
import java.util.UUID;

public class AuditExportTokenClaims {
    private String jti;
    private UUID jobId;
    private UUID organizationId;
    private UUID userId;
    private Instant issuedAt;
    private Instant expiresAt;

    public AuditExportTokenClaims() {
    }

    public AuditExportTokenClaims(String jti, UUID jobId, UUID organizationId, UUID userId, Instant issuedAt, Instant expiresAt) {
        this.jti = jti;
        this.jobId = jobId;
        this.organizationId = organizationId;
        this.userId = userId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
