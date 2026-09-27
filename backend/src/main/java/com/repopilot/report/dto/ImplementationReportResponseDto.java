package com.repopilot.report.dto;

import com.repopilot.report.entity.ReportStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class ImplementationReportResponseDto {

    private UUID id;
    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID taskExecutionId;
    private ReportStatus status;
    private String executionOutcome;
    private String changedFilesSummary;
    private String testSummary;
    private String securityReviewSummary;
    private BigDecimal confidenceScore;
    private String sourceContextHash;
    private String correlationId;
    private String failureReason;
    private Instant createdAt;

    public ImplementationReportResponseDto() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getTaskExecutionId() {
        return taskExecutionId;
    }

    public void setTaskExecutionId(UUID taskExecutionId) {
        this.taskExecutionId = taskExecutionId;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public String getExecutionOutcome() {
        return executionOutcome;
    }

    public void setExecutionOutcome(String executionOutcome) {
        this.executionOutcome = executionOutcome;
    }

    public String getChangedFilesSummary() {
        return changedFilesSummary;
    }

    public void setChangedFilesSummary(String changedFilesSummary) {
        this.changedFilesSummary = changedFilesSummary;
    }

    public String getTestSummary() {
        return testSummary;
    }

    public void setTestSummary(String testSummary) {
        this.testSummary = testSummary;
    }

    public String getSecurityReviewSummary() {
        return securityReviewSummary;
    }

    public void setSecurityReviewSummary(String securityReviewSummary) {
        this.securityReviewSummary = securityReviewSummary;
    }

    public BigDecimal getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(BigDecimal confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getSourceContextHash() {
        return sourceContextHash;
    }

    public void setSourceContextHash(String sourceContextHash) {
        this.sourceContextHash = sourceContextHash;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
