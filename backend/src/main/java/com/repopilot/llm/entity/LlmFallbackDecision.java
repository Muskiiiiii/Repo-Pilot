package com.repopilot.llm.entity;

import com.repopilot.organization.entity.Organization;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_fallback_decisions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_fallback_decision_trigger", columnNames = {"source_attempt_id", "decision_sequence"}),
    @UniqueConstraint(name = "uk_fallback_decision_target", columnNames = {"target_attempt_id"})
})
public class LlmFallbackDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "logical_request_id", nullable = false)
    private LlmLogicalRequest logicalRequest;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_attempt_id", nullable = false)
    private LlmProviderAttempt sourceAttempt;

    @Column(name = "decision_sequence", nullable = false)
    private Integer decisionSequence;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_attempt_id")
    private LlmProviderAttempt targetAttempt;

    @Column(name = "original_provider_id", nullable = false, length = 32)
    private String originalProviderId;

    @Column(name = "original_model_id", nullable = false, length = 64)
    private String originalModelId;

    @Column(name = "target_provider_id", length = 32)
    private String targetProviderId;

    @Column(name = "target_model_id", length = 64)
    private String targetModelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_status", nullable = false, length = 32)
    private LlmFallbackDecisionStatus decisionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_safety_classification", nullable = false, length = 32)
    private LlmTriggerSafetyClassification triggerSafetyClassification;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exceptional_policy_id")
    private LlmExceptionalFallbackPolicy exceptionalPolicy;

    @Column(name = "rejection_reason", length = 64)
    private String rejectionReason;

    @Version
    @Column(name = "fencing_version", nullable = false)
    private Long fencingVersion = 1L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public LlmFallbackDecision() {
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

    public LlmLogicalRequest getLogicalRequest() {
        return logicalRequest;
    }

    public void setLogicalRequest(LlmLogicalRequest logicalRequest) {
        this.logicalRequest = logicalRequest;
    }

    public LlmProviderAttempt getSourceAttempt() {
        return sourceAttempt;
    }

    public void setSourceAttempt(LlmProviderAttempt sourceAttempt) {
        this.sourceAttempt = sourceAttempt;
    }

    public Integer getDecisionSequence() {
        return decisionSequence;
    }

    public void setDecisionSequence(Integer decisionSequence) {
        this.decisionSequence = decisionSequence;
    }

    public LlmProviderAttempt getTargetAttempt() {
        return targetAttempt;
    }

    public void setTargetAttempt(LlmProviderAttempt targetAttempt) {
        this.targetAttempt = targetAttempt;
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

    public LlmExceptionalFallbackPolicy getExceptionalPolicy() {
        return exceptionalPolicy;
    }

    public void setExceptionalPolicy(LlmExceptionalFallbackPolicy exceptionalPolicy) {
        this.exceptionalPolicy = exceptionalPolicy;
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
