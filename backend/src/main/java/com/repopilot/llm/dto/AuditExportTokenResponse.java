package com.repopilot.llm.dto;

import java.time.Instant;
import java.util.UUID;

public class AuditExportTokenResponse {
    private String token;
    private Instant expiresAt;
    private UUID jobId;
    private UUID organizationId;

    public AuditExportTokenResponse() {
    }

    public AuditExportTokenResponse(String token, Instant expiresAt, UUID jobId, UUID organizationId) {
        this.token = token;
        this.expiresAt = expiresAt;
        this.jobId = jobId;
        this.organizationId = organizationId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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
}
