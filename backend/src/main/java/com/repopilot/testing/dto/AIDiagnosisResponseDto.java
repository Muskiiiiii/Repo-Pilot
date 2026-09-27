package com.repopilot.testing.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AIDiagnosisResponseDto {

    private UUID diagnosisId;
    private UUID repairIterationId;
    private UUID executionId;
    private Integer iterationNumber;
    private String status;
    private String failureCategory;
    private String rootCauseDescription;
    private String failureMechanism;
    private String proposedFixStrategy;
    private String confidenceScore;
    private List<String> affectedFiles = new ArrayList<>();
    private String repairRecommendation;
    private String modelIdentifier;
    private String rejectionReason;
    private Instant createdAt;

    public AIDiagnosisResponseDto() {
    }

    public UUID getDiagnosisId() {
        return diagnosisId;
    }

    public void setDiagnosisId(UUID diagnosisId) {
        this.diagnosisId = diagnosisId;
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

    public Integer getIterationNumber() {
        return iterationNumber;
    }

    public void setIterationNumber(Integer iterationNumber) {
        this.iterationNumber = iterationNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
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

    public List<String> getAffectedFiles() {
        return affectedFiles;
    }

    public void setAffectedFiles(List<String> affectedFiles) {
        this.affectedFiles = affectedFiles;
    }

    public String getRepairRecommendation() {
        return repairRecommendation;
    }

    public void setRepairRecommendation(String repairRecommendation) {
        this.repairRecommendation = repairRecommendation;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
