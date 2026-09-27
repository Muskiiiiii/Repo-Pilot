package com.repopilot.llm.dto;

import java.time.Instant;
import java.util.UUID;

public class ExceptionalPolicyResponse {

    private UUID id;
    private UUID organizationId;
    private String sourceAuthorityType;
    private String originalProviderId;
    private String targetProviderId;
    private Integer policyVersion;
    private UUID createdByUserId;
    private String reason;
    private Instant createdAt;
    private boolean active;

    public ExceptionalPolicyResponse() {
    }

    public ExceptionalPolicyResponse(UUID id,
                                     UUID organizationId,
                                     String sourceAuthorityType,
                                     String originalProviderId,
                                     String targetProviderId,
                                     Integer policyVersion,
                                     UUID createdByUserId,
                                     String reason,
                                     Instant createdAt,
                                     boolean active) {
        this.id = id;
        this.organizationId = organizationId;
        this.sourceAuthorityType = sourceAuthorityType;
        this.originalProviderId = originalProviderId;
        this.targetProviderId = targetProviderId;
        this.policyVersion = policyVersion;
        this.createdByUserId = createdByUserId;
        this.reason = reason;
        this.createdAt = createdAt;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public String getSourceAuthorityType() {
        return sourceAuthorityType;
    }

    public void setSourceAuthorityType(String sourceAuthorityType) {
        this.sourceAuthorityType = sourceAuthorityType;
    }

    public String getOriginalProviderId() {
        return originalProviderId;
    }

    public void setOriginalProviderId(String originalProviderId) {
        this.originalProviderId = originalProviderId;
    }

    public String getTargetProviderId() {
        return targetProviderId;
    }

    public void setTargetProviderId(String targetProviderId) {
        this.targetProviderId = targetProviderId;
    }

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(Integer policyVersion) {
        this.policyVersion = policyVersion;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
