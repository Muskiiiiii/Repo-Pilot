package com.repopilot.testing.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repair_proposals")
public class AIRepairProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repair_iteration_id", nullable = false)
    private RepairIteration repairIteration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagnosis_id")
    private AIDiagnosis diagnosis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "file_modifications", nullable = false, columnDefinition = "jsonb")
    private String fileModifications;

    @Column(name = "validation_status", nullable = false, length = 50)
    private String validationStatus = "PENDING"; // PENDING, VALIDATED, REJECTED, STALE_REPAIR_PROPOSAL

    @Column(name = "validation_reason", columnDefinition = "TEXT")
    private String validationReason;

    @Column(name = "parent_snapshot_hash", nullable = false, length = 64)
    private String parentSnapshotHash;

    @Column(name = "resulting_tree_hash", length = 64)
    private String resultingTreeHash;

    @Column(name = "diff_hash", nullable = false, length = 64)
    private String diffHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @Column(name = "model_identifier", nullable = false, length = 100)
    private String modelIdentifier;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public AIRepairProposal() {
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

    public AIDiagnosis getDiagnosis() {
        return diagnosis;
    }

    public void setDiagnosis(AIDiagnosis diagnosis) {
        this.diagnosis = diagnosis;
    }

    public String getFileModifications() {
        return fileModifications;
    }

    public void setFileModifications(String fileModifications) {
        this.fileModifications = fileModifications;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(String validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getValidationReason() {
        return validationReason;
    }

    public void setValidationReason(String validationReason) {
        this.validationReason = validationReason;
    }

    public String getParentSnapshotHash() {
        return parentSnapshotHash;
    }

    public void setParentSnapshotHash(String parentSnapshotHash) {
        this.parentSnapshotHash = parentSnapshotHash;
    }

    public String getResultingTreeHash() {
        return resultingTreeHash;
    }

    public void setResultingTreeHash(String resultingTreeHash) {
        this.resultingTreeHash = resultingTreeHash;
    }

    public String getDiffHash() {
        return diffHash;
    }

    public void setDiffHash(String diffHash) {
        this.diffHash = diffHash;
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
