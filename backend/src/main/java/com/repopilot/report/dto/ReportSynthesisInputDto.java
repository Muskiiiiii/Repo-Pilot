package com.repopilot.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

public class ReportSynthesisInputDto {

    private UUID implementationReportId;
    private UUID taskExecutionId;
    private UUID projectId;
    private UUID repositoryId;
    private String taskTitle;
    private String executionOutcome;
    private String sourceContextHash;
    private String changedFilesSummaryJson;
    private String testSummaryJson;
    private String securityReviewSummaryJson;
    private BigDecimal confidenceScore;
    private String failureReason;
    private String synthesizerVersion = "v1.0";
    private String promptVersion = "v1.0";
    private String validatorVersion = "v1.0";

    public ReportSynthesisInputDto() {
    }

    public UUID getImplementationReportId() {
        return implementationReportId;
    }

    public void setImplementationReportId(UUID implementationReportId) {
        this.implementationReportId = implementationReportId;
    }

    public UUID getTaskExecutionId() {
        return taskExecutionId;
    }

    public void setTaskExecutionId(UUID taskExecutionId) {
        this.taskExecutionId = taskExecutionId;
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

    public String getTaskTitle() {
        return taskTitle;
    }

    public void setTaskTitle(String taskTitle) {
        this.taskTitle = taskTitle;
    }

    public String getExecutionOutcome() {
        return executionOutcome;
    }

    public void setExecutionOutcome(String executionOutcome) {
        this.executionOutcome = executionOutcome;
    }

    public String getSourceContextHash() {
        return sourceContextHash;
    }

    public void setSourceContextHash(String sourceContextHash) {
        this.sourceContextHash = sourceContextHash;
    }

    public String getChangedFilesSummaryJson() {
        return changedFilesSummaryJson;
    }

    public void setChangedFilesSummaryJson(String changedFilesSummaryJson) {
        this.changedFilesSummaryJson = changedFilesSummaryJson;
    }

    public String getTestSummaryJson() {
        return testSummaryJson;
    }

    public void setTestSummaryJson(String testSummaryJson) {
        this.testSummaryJson = testSummaryJson;
    }

    public String getSecurityReviewSummaryJson() {
        return securityReviewSummaryJson;
    }

    public void setSecurityReviewSummaryJson(String securityReviewSummaryJson) {
        this.securityReviewSummaryJson = securityReviewSummaryJson;
    }

    public BigDecimal getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(BigDecimal confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getSynthesizerVersion() {
        return synthesizerVersion;
    }

    public void setSynthesizerVersion(String synthesizerVersion) {
        this.synthesizerVersion = synthesizerVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getValidatorVersion() {
        return validatorVersion;
    }

    public void setValidatorVersion(String validatorVersion) {
        this.validatorVersion = validatorVersion;
    }
}
