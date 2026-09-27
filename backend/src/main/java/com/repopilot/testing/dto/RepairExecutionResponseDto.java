package com.repopilot.testing.dto;

import java.time.Instant;
import java.util.UUID;

public class RepairExecutionResponseDto {

    private UUID repairIterationId;
    private UUID executionId;
    private UUID proposalId;
    private UUID testRunId;
    private Integer iterationNumber;
    private String outcomePolicy; // REPAIR_SUCCESS, REPAIR_FAILED_SAME_FAILURE, REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE, REPAIR_FAILED_NON_REPAIRABLE_FAILURE, REPAIR_FAILED_AMBIGUOUS, REPAIR_FAILED_TIMEOUT, REPAIR_FAILED_OOM, REPAIR_FAILED_RESOURCE_LIMIT, REPAIR_FAILED_INFRASTRUCTURE, REPAIR_CANCELLED, REPAIR_SECURITY_REJECTED, REPAIR_STALE, REPAIR_DUPLICATE
    private String iterationStatus; // VERIFIED, EXECUTION_FAILED, TIMED_OUT, NOT_REPAIRABLE, CANCELLED, VALIDATION_FAILED
    private String failureCategory;
    private String repairability;
    private String confidence;
    private String summaryReason;
    private String parentSnapshotHash;
    private String resultingTreeHash;
    private TestRunResponseDto testRun;
    private Instant completedAt;

    public RepairExecutionResponseDto() {
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

    public UUID getProposalId() {
        return proposalId;
    }

    public void setProposalId(UUID proposalId) {
        this.proposalId = proposalId;
    }

    public UUID getTestRunId() {
        return testRunId;
    }

    public void setTestRunId(UUID testRunId) {
        this.testRunId = testRunId;
    }

    public Integer getIterationNumber() {
        return iterationNumber;
    }

    public void setIterationNumber(Integer iterationNumber) {
        this.iterationNumber = iterationNumber;
    }

    public String getOutcomePolicy() {
        return outcomePolicy;
    }

    public void setOutcomePolicy(String outcomePolicy) {
        this.outcomePolicy = outcomePolicy;
    }

    public String getIterationStatus() {
        return iterationStatus;
    }

    public void setIterationStatus(String iterationStatus) {
        this.iterationStatus = iterationStatus;
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

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getSummaryReason() {
        return summaryReason;
    }

    public void setSummaryReason(String summaryReason) {
        this.summaryReason = summaryReason;
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

    public TestRunResponseDto getTestRun() {
        return testRun;
    }

    public void setTestRun(TestRunResponseDto testRun) {
        this.testRun = testRun;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
