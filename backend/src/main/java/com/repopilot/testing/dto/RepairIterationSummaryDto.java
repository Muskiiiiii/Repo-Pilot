package com.repopilot.testing.dto;

import java.time.Instant;
import java.util.UUID;

public class RepairIterationSummaryDto {

    private UUID iterationId;
    private int iterationNumber;
    private String status;
    private String eligibilityStatus;
    private String diagnosisStatus;
    private String proposalStatus;
    private String physicalApplicationStatus;
    private String retestStatus;
    private String failureCategory;
    private String repairability;
    private Instant startedAt;
    private Instant completedAt;

    public RepairIterationSummaryDto() {}

    public UUID getIterationId() {
        return iterationId;
    }

    public void setIterationId(UUID iterationId) {
        this.iterationId = iterationId;
    }

    public int getIterationNumber() {
        return iterationNumber;
    }

    public void setIterationNumber(int iterationNumber) {
        this.iterationNumber = iterationNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getEligibilityStatus() {
        return eligibilityStatus;
    }

    public void setEligibilityStatus(String eligibilityStatus) {
        this.eligibilityStatus = eligibilityStatus;
    }

    public String getDiagnosisStatus() {
        return diagnosisStatus;
    }

    public void setDiagnosisStatus(String diagnosisStatus) {
        this.diagnosisStatus = diagnosisStatus;
    }

    public String getProposalStatus() {
        return proposalStatus;
    }

    public void setProposalStatus(String proposalStatus) {
        this.proposalStatus = proposalStatus;
    }

    public String getPhysicalApplicationStatus() {
        return physicalApplicationStatus;
    }

    public void setPhysicalApplicationStatus(String physicalApplicationStatus) {
        this.physicalApplicationStatus = physicalApplicationStatus;
    }

    public String getRetestStatus() {
        return retestStatus;
    }

    public void setRetestStatus(String retestStatus) {
        this.retestStatus = retestStatus;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    public String getRepairability() {
        return repairability;
    }

    public void setRepairability(String repairability) {
        this.repairability = repairability;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
