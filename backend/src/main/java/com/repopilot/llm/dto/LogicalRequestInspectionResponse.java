package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmBillingStatus;
import com.repopilot.llm.entity.LlmExecutionStatus;
import com.repopilot.llm.entity.LlmLogicalRequest;

import java.time.Instant;
import java.util.UUID;

public class LogicalRequestInspectionResponse {

    private UUID logicalRequestId;
    private UUID organizationId;
    private UUID projectId;
    private String sourceAuthorityType;
    private String sourceAuthorityId;
    private String idempotencyKey;
    private String featureName;
    private LlmExecutionStatus executionStatus;
    private LlmBillingStatus billingStatus;
    private Integer currentAttemptCount;
    private Long fencingVersion;
    private Integer totalTokensUsed;
    private Long totalBilledCostMicroUsd;
    private Instant createdAt;
    private Instant completedAt;

    public LogicalRequestInspectionResponse() {
    }

    public static LogicalRequestInspectionResponse fromEntity(LlmLogicalRequest entity) {
        if (entity == null) return null;
        LogicalRequestInspectionResponse dto = new LogicalRequestInspectionResponse();
        dto.setLogicalRequestId(entity.getId());
        dto.setOrganizationId(entity.getOrganization() != null ? entity.getOrganization().getId() : null);
        dto.setProjectId(entity.getProject() != null ? entity.getProject().getId() : null);
        dto.setSourceAuthorityType(entity.getSourceAuthorityType());
        dto.setSourceAuthorityId(entity.getSourceAuthorityId());
        dto.setIdempotencyKey(entity.getIdempotencyKey());
        dto.setFeatureName(entity.getFeatureName());
        dto.setExecutionStatus(entity.getExecutionStatus());
        dto.setBillingStatus(entity.getBillingStatus());
        dto.setCurrentAttemptCount(entity.getCurrentAttemptCount());
        dto.setFencingVersion(entity.getFencingVersion());
        dto.setTotalTokensUsed(entity.getTotalTokensUsed());
        dto.setTotalBilledCostMicroUsd(entity.getTotalBilledCostMicroUsd());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        return dto;
    }

    public UUID getLogicalRequestId() {
        return logicalRequestId;
    }

    public void setLogicalRequestId(UUID logicalRequestId) {
        this.logicalRequestId = logicalRequestId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
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
