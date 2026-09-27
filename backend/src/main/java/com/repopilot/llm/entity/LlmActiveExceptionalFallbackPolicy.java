package com.repopilot.llm.entity;

import com.repopilot.organization.entity.Organization;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_active_exceptional_fallback_policies", uniqueConstraints = {
    @UniqueConstraint(name = "uk_llm_active_exceptional_policy", columnNames = {"organization_id", "source_authority_type", "original_provider_id", "target_provider_id"})
})
public class LlmActiveExceptionalFallbackPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(name = "source_authority_type", nullable = false, length = 64)
    private String sourceAuthorityType;

    @Column(name = "original_provider_id", nullable = false, length = 32)
    private String originalProviderId;

    @Column(name = "target_provider_id", nullable = false, length = 32)
    private String targetProviderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "active_policy_id", nullable = false)
    private LlmExceptionalFallbackPolicy activePolicy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public LlmActiveExceptionalFallbackPolicy() {
    }

    public LlmActiveExceptionalFallbackPolicy(Organization organization, String sourceAuthorityType, String originalProviderId, String targetProviderId, LlmExceptionalFallbackPolicy activePolicy) {
        this.organization = organization;
        this.sourceAuthorityType = sourceAuthorityType;
        this.originalProviderId = originalProviderId;
        this.targetProviderId = targetProviderId;
        this.activePolicy = activePolicy;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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

    public LlmExceptionalFallbackPolicy getActivePolicy() {
        return activePolicy;
    }

    public void setActivePolicy(LlmExceptionalFallbackPolicy activePolicy) {
        this.activePolicy = activePolicy;
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
