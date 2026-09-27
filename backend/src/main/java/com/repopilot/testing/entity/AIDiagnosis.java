package com.repopilot.testing.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_diagnoses")
public class AIDiagnosis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repair_iteration_id", nullable = false)
    private RepairIteration repairIteration;

    @Column(name = "root_cause_description", nullable = false, columnDefinition = "TEXT")
    private String rootCauseDescription;

    @Column(name = "failure_mechanism", nullable = false, length = 50)
    private String failureMechanism;

    @Column(name = "proposed_fix_strategy", columnDefinition = "TEXT")
    private String proposedFixStrategy;

    @Column(name = "confidence_score", nullable = false, length = 20)
    private String confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @Column(name = "model_identifier", nullable = false, length = 100)
    private String modelIdentifier;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public AIDiagnosis() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public RepairIteration getRepairIteration() {
        return repairIteration;
    }

    public void setRepairIteration(RepairIteration repairIteration) {
        this.repairIteration = repairIteration;
    }

    public String getRootCauseDescription() {
        return rootCauseDescription;
    }

    public void setRootCauseDescription(String rootCauseDescription) {
        this.rootCauseDescription = rootCauseDescription;
    }

    public String getFailureMechanism() {
        return failureMechanism;
    }

    public void setFailureMechanism(String failureMechanism) {
        this.failureMechanism = failureMechanism;
    }

    public String getProposedFixStrategy() {
        return proposedFixStrategy;
    }

    public void setProposedFixStrategy(String proposedFixStrategy) {
        this.proposedFixStrategy = proposedFixStrategy;
    }

    public String getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(String confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
