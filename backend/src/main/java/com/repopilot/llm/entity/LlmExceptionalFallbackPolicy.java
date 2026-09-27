package com.repopilot.llm.entity;

import com.repopilot.organization.entity.Organization;
import com.repopilot.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_exceptional_fallback_policies", uniqueConstraints = {
    @UniqueConstraint(name = "uk_llm_exceptional_policy_ver", columnNames = {"organization_id", "source_authority_type", "original_provider_id", "target_provider_id", "policy_version"}),
    @UniqueConstraint(name = "uk_llm_exceptional_policy_identity", columnNames = {"id", "organization_id", "source_authority_type", "original_provider_id", "target_provider_id"}),
    @UniqueConstraint(name = "uk_llm_exceptional_policy_id_org", columnNames = {"id", "organization_id"})
})
public class LlmExceptionalFallbackPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(name = "source_authority_type", nullable = false, length = 64, updatable = false)
    private String sourceAuthorityType;

    @Column(name = "original_provider_id", nullable = false, length = 32, updatable = false)
    private String originalProviderId;

    @Column(name = "target_provider_id", nullable = false, length = 32, updatable = false)
    private String targetProviderId;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private Integer policyVersion = 1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id", updatable = false)
    private User createdByUser;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public LlmExceptionalFallbackPolicy() {
    }

    public LlmExceptionalFallbackPolicy(Organization organization, String sourceAuthorityType, String originalProviderId, String targetProviderId, Integer policyVersion, User createdByUser, String reason) {
        this.organization = organization;
        this.sourceAuthorityType = sourceAuthorityType;
        this.originalProviderId = originalProviderId;
        this.targetProviderId = targetProviderId;
        this.policyVersion = policyVersion != null ? policyVersion : 1;
        this.createdByUser = createdByUser;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
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

    public User getCreatedByUser() {
        return createdByUser;
    }

    public void setCreatedByUser(User createdByUser) {
        this.createdByUser = createdByUser;
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
}
