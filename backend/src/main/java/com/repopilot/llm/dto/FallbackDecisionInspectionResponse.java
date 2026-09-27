package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmFallbackDecision;
import com.repopilot.llm.entity.LlmFallbackDecisionStatus;
import com.repopilot.llm.entity.LlmTriggerSafetyClassification;

import java.time.Instant;
import java.util.UUID;

public class FallbackDecisionInspectionResponse {

    private UUID decisionId;
    private UUID organizationId;
    private UUID logicalRequestId;
    private UUID sourceAttemptId;
    private UUID targetAttemptId;
    private Integer decisionSequence;
    private String originalProviderId;
    private String originalModelId;
    private String targetProviderId;
    private String targetModelId;
    private LlmFallbackDecisionStatus decisionStatus;
    private LlmTriggerSafetyClassification triggerSafetyClassification;
    private UUID reservationId;
    private UUID exceptionalPolicyId;
    private Integer exceptionalPolicyVersion;
    private String rejectionReason;
    private Long fencingVersion;
    private Instant createdAt;
    private Instant completedAt;

    public FallbackDecisionInspectionResponse() {
    }

    public static FallbackDecisionInspectionResponse fromEntity(LlmFallbackDecision entity) {
        if (entity == null) return null;
        FallbackDecisionInspectionResponse dto = new FallbackDecisionInspectionResponse();
        dto.setDecisionId(entity.getId());
        dto.setOrganizationId(entity.getOrganization() != null ? entity.getOrganization().getId() : null);
        dto.setLogicalRequestId(entity.getLogicalRequest() != null ? entity.getLogicalRequest().getId() : null);
        dto.setSourceAttemptId(entity.getSourceAttempt() != null ? entity.getSourceAttempt().getId() : null);
        dto.setTargetAttemptId(entity.getTargetAttempt() != null ? entity.getTargetAttempt().getId() : null);
        dto.setDecisionSequence(entity.getDecisionSequence());
        dto.setOriginalProviderId(entity.getOriginalProviderId());
        dto.setOriginalModelId(entity.getOriginalModelId());
        dto.setTargetProviderId(entity.getTargetProviderId());
        dto.setTargetModelId(entity.getTargetModelId());
        dto.setDecisionStatus(entity.getDecisionStatus());
        dto.setTriggerSafetyClassification(entity.getTriggerSafetyClassification());
        dto.setReservationId(entity.getReservationId());
        if (entity.getExceptionalPolicy() != null) {
            dto.setExceptionalPolicyId(entity.getExceptionalPolicy().getId());
            dto.setExceptionalPolicyVersion(entity.getExceptionalPolicy().getPolicyVersion());
        }
        dto.setRejectionReason(entity.getRejectionReason());
        dto.setFencingVersion(entity.getFencingVersion());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setCompletedAt(entity.getCompletedAt());
        return dto;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(UUID decisionId) {
        this.decisionId = decisionId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getLogicalRequestId() {
        return logicalRequestId;
    }

    public void setLogicalRequestId(UUID logicalRequestId) {
        this.logicalRequestId = logicalRequestId;
    }

    public UUID getSourceAttemptId() {
        return sourceAttemptId;
    }

    public void setSourceAttemptId(UUID sourceAttemptId) {
        this.sourceAttemptId = sourceAttemptId;
    }

    public UUID getTargetAttemptId() {
        return targetAttemptId;
    }

    public void setTargetAttemptId(UUID targetAttemptId) {
        this.targetAttemptId = targetAttemptId;
    }

    public Integer getDecisionSequence() {
        return decisionSequence;
    }

    public void setDecisionSequence(Integer decisionSequence) {
        this.decisionSequence = decisionSequence;
    }

    public String getOriginalProviderId() {
        return originalProviderId;
    }

    public void setOriginalProviderId(String originalProviderId) {
        this.originalProviderId = originalProviderId;
    }

    public String getOriginalModelId() {
        return originalModelId;
    }

    public void setOriginalModelId(String originalModelId) {
        this.originalModelId = originalModelId;
    }

    public String getTargetProviderId() {
        return targetProviderId;
    }

    public void setTargetProviderId(String targetProviderId) {
        this.targetProviderId = targetProviderId;
    }

    public String getTargetModelId() {
        return targetModelId;
    }

    public void setTargetModelId(String targetModelId) {
        this.targetModelId = targetModelId;
    }

    public LlmFallbackDecisionStatus getDecisionStatus() {
        return decisionStatus;
    }

    public void setDecisionStatus(LlmFallbackDecisionStatus decisionStatus) {
        this.decisionStatus = decisionStatus;
    }

    public LlmTriggerSafetyClassification getTriggerSafetyClassification() {
        return triggerSafetyClassification;
    }

    public void setTriggerSafetyClassification(LlmTriggerSafetyClassification triggerSafetyClassification) {
        this.triggerSafetyClassification = triggerSafetyClassification;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public void setReservationId(UUID reservationId) {
        this.reservationId = reservationId;
    }

    public UUID getExceptionalPolicyId() {
        return exceptionalPolicyId;
    }

    public void setExceptionalPolicyId(UUID exceptionalPolicyId) {
        this.exceptionalPolicyId = exceptionalPolicyId;
    }

    public Integer getExceptionalPolicyVersion() {
        return exceptionalPolicyVersion;
    }

    public void setExceptionalPolicyVersion(Integer exceptionalPolicyVersion) {
        this.exceptionalPolicyVersion = exceptionalPolicyVersion;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
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
