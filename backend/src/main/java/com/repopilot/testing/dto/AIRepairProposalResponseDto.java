package com.repopilot.testing.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AIRepairProposalResponseDto {

    private UUID proposalId;
    private UUID repairIterationId;
    private UUID executionId;
    private UUID diagnosisId;
    private Integer iterationNumber;
    private String validationStatus; // VALIDATED, REJECTED, STALE_REPAIR_PROPOSAL
    private String validationReason;
    private String parentSnapshotHash;
    private String resultingTreeHash;
    private String diffHash;
    private String rationale;
    private List<ProposedFileModificationDto> fileModifications = new ArrayList<>();
    private String modelIdentifier;
    private Instant createdAt;

    public AIRepairProposalResponseDto() {
    }

    public UUID getProposalId() {
        return proposalId;
    }

    public void setProposalId(UUID proposalId) {
        this.proposalId = proposalId;
    }

    public UUID getRepairIterationId() {
        return repairIterationId;
    }

    public void setRepairIterationId(UUID repairIterationId) {
        this.repairIterationId = repairIterationId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public UUID getDiagnosisId() {
        return diagnosisId;
    }

    public void setDiagnosisId(UUID diagnosisId) {
        this.diagnosisId = diagnosisId;
    }

    public Integer getIterationNumber() {
        return iterationNumber;
    }

    public void setIterationNumber(Integer iterationNumber) {
        this.iterationNumber = iterationNumber;
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

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public List<ProposedFileModificationDto> getFileModifications() {
        return fileModifications;
    }

    public void setFileModifications(List<ProposedFileModificationDto> fileModifications) {
        this.fileModifications = fileModifications;
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
