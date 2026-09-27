package com.repopilot.llm.dto;

import java.time.Instant;
import java.util.UUID;

public class ActiveExceptionalPolicyResponse {

    private UUID id;
    private UUID organizationId;
    private String sourceAuthorityType;
    private String originalProviderId;
    private String targetProviderId;
    private UUID activePolicyId;
    private Integer activePolicyVersion;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public ActiveExceptionalPolicyResponse() {
    }

    public ActiveExceptionalPolicyResponse(UUID id,
                                           UUID organizationId,
                                           String sourceAuthorityType,
                                           String originalProviderId,
                                           String targetProviderId,
                                           UUID activePolicyId,
                                           Integer activePolicyVersion,
                                           Long version,
                                           Instant createdAt,
                                           Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.sourceAuthorityType = sourceAuthorityType;
        this.originalProviderId = originalProviderId;
        this.targetProviderId = targetProviderId;
        this.activePolicyId = activePolicyId;
        this.activePolicyVersion = activePolicyVersion;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public UUID getActivePolicyId() {
        return activePolicyId;
    }

    public void setActivePolicyId(UUID activePolicyId) {
        this.activePolicyId = activePolicyId;
    }

    public Integer getActivePolicyVersion() {
        return activePolicyVersion;
    }

    public void setActivePolicyVersion(Integer activePolicyVersion) {
        this.activePolicyVersion = activePolicyVersion;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
