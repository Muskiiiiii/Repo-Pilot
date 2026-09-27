package com.repopilot.llm.entity;

import com.repopilot.organization.entity.Organization;
import com.repopilot.project.entity.Project;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_logical_requests", uniqueConstraints = {
    @UniqueConstraint(name = "uk_llm_logical_source", columnNames = {"source_authority_type", "source_authority_id", "idempotency_key"})
})
public class LlmLogicalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "source_authority_type", nullable = false, length = 64)
    private String sourceAuthorityType;

    @Column(name = "source_authority_id", nullable = false, length = 128)
    private String sourceAuthorityId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "feature_name", nullable = false, length = 64)
    private String featureName;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 32)
    private LlmExecutionStatus executionStatus = LlmExecutionStatus.ATTEMPT_CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_status", nullable = false, length = 32)
    private LlmBillingStatus billingStatus = LlmBillingStatus.UNBILLED;

    @Column(name = "current_attempt_count", nullable = false)
    private Integer currentAttemptCount = 0;

    @Column(name = "fencing_version", nullable = false)
    private Long fencingVersion = 1L;

    @Column(name = "total_tokens_used", nullable = false)
    private Integer totalTokensUsed = 0;

    @Column(name = "total_billed_cost_micro_usd", nullable = false)
    private Long totalBilledCostMicroUsd = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public LlmLogicalRequest() {
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

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public String getSourceAuthorityType() {
        return sourceAuthorityType;
    }

    public void setSourceAuthorityType(String sourceAuthorityType) {
        this.sourceAuthorityType = sourceAuthorityType;
    }

    public String getSourceAuthorityId() {
        return sourceAuthorityId;
    }

    public void setSourceAuthorityId(String sourceAuthorityId) {
        this.sourceAuthorityId = sourceAuthorityId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFeatureName() {
        return featureName;
    }

    public void setFeatureName(String featureName) {
        this.featureName = featureName;
    }

    public LlmExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(LlmExecutionStatus executionStatus) {
        this.executionStatus = executionStatus;
    }

    public LlmBillingStatus getBillingStatus() {
        return billingStatus;
    }

    public void setBillingStatus(LlmBillingStatus billingStatus) {
        this.billingStatus = billingStatus;
    }

    public Integer getCurrentAttemptCount() {
        return currentAttemptCount;
    }

    public void setCurrentAttemptCount(Integer currentAttemptCount) {
        this.currentAttemptCount = currentAttemptCount;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
    }

    public Integer getTotalTokensUsed() {
        return totalTokensUsed;
    }

    public void setTotalTokensUsed(Integer totalTokensUsed) {
        this.totalTokensUsed = totalTokensUsed;
    }

    public Long getTotalBilledCostMicroUsd() {
        return totalBilledCostMicroUsd;
    }

    public void setTotalBilledCostMicroUsd(Long totalBilledCostMicroUsd) {
        this.totalBilledCostMicroUsd = totalBilledCostMicroUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
