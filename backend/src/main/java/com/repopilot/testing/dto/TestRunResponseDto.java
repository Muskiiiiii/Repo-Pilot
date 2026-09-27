package com.repopilot.testing.dto;

import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;

import java.time.Instant;
import java.util.UUID;

public class TestRunResponseDto {

    private UUID id;
    private UUID taskId;
    private UUID executionId;
    private Integer executionNumber;
    private Ecosystem ecosystem;
    private String testTarget;
    private String command;
    private String status;
    private FailureType failureType;
    private Integer exitCode;
    private Long durationMs;
    private String stdoutSummary;
    private String stderrSummary;
    private Boolean stdoutTruncated;
    private Boolean stderrTruncated;
    private com.repopilot.testing.entity.TerminationReason terminationReason;
    private Long stdoutBytesObserved;
    private Long stderrBytesObserved;
    private Integer testsRun;
    private Integer failuresCount;
    private Integer errorsCount;
    private Integer skippedCount;
    private Integer passedCount;
    private String parserStatus;
    private Object failureEvidence;
    private FailureType failureCategory;
    private com.repopilot.testing.entity.Repairability repairability;
    private com.repopilot.testing.entity.ConfidenceLevel confidence;
    private String classificationReason;
    private String repairBlockReason;
    private String classifierVersion;
    private Instant classifiedAt;
    private Boolean isRelatedToCodeChanges;
    private Object classificationEvidence;
    private Instant startedAt;
    private Instant completedAt;

    public TestRunResponseDto() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public Integer getExecutionNumber() {
        return executionNumber;
    }

    public void setExecutionNumber(Integer executionNumber) {
        this.executionNumber = executionNumber;
    }

    public Ecosystem getEcosystem() {
        return ecosystem;
    }

    public void setEcosystem(Ecosystem ecosystem) {
        this.ecosystem = ecosystem;
    }

    public String getTestTarget() {
        return testTarget;
    }

    public void setTestTarget(String testTarget) {
        this.testTarget = testTarget;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public void setFailureType(FailureType failureType) {
        this.failureType = failureType;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getStdoutSummary() {
        return stdoutSummary;
    }

    public void setStdoutSummary(String stdoutSummary) {
        this.stdoutSummary = stdoutSummary;
    }

    public String getStderrSummary() {
        return stderrSummary;
    }

    public void setStderrSummary(String stderrSummary) {
        this.stderrSummary = stderrSummary;
    }

    public Boolean getStdoutTruncated() {
        return stdoutTruncated;
    }

    public void setStdoutTruncated(Boolean stdoutTruncated) {
        this.stdoutTruncated = stdoutTruncated;
    }

    public Boolean getStderrTruncated() {
        return stderrTruncated;
    }

    public void setStderrTruncated(Boolean stderrTruncated) {
        this.stderrTruncated = stderrTruncated;
    }

    public com.repopilot.testing.entity.TerminationReason getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(com.repopilot.testing.entity.TerminationReason terminationReason) {
        this.terminationReason = terminationReason;
    }

    public Long getStdoutBytesObserved() {
        return stdoutBytesObserved;
    }

    public void setStdoutBytesObserved(Long stdoutBytesObserved) {
        this.stdoutBytesObserved = stdoutBytesObserved;
    }

    public Long getStderrBytesObserved() {
        return stderrBytesObserved;
    }

    public void setStderrBytesObserved(Long stderrBytesObserved) {
        this.stderrBytesObserved = stderrBytesObserved;
    }

    public Integer getTestsRun() {
        return testsRun;
    }

    public void setTestsRun(Integer testsRun) {
        this.testsRun = testsRun;
    }

    public Integer getFailuresCount() {
        return failuresCount;
    }

    public void setFailuresCount(Integer failuresCount) {
        this.failuresCount = failuresCount;
    }

    public Integer getErrorsCount() {
        return errorsCount;
    }

    public void setErrorsCount(Integer errorsCount) {
        this.errorsCount = errorsCount;
    }

    public Integer getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(Integer skippedCount) {
        this.skippedCount = skippedCount;
    }

    public Integer getPassedCount() {
        return passedCount;
    }

    public void setPassedCount(Integer passedCount) {
        this.passedCount = passedCount;
    }

    public String getParserStatus() {
        return parserStatus;
    }

    public void setParserStatus(String parserStatus) {
        this.parserStatus = parserStatus;
    }

    public Object getFailureEvidence() {
        return failureEvidence;
    }

    public void setFailureEvidence(Object failureEvidence) {
        this.failureEvidence = failureEvidence;
    }

    public FailureType getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(FailureType failureCategory) {
        this.failureCategory = failureCategory;
    }

    public com.repopilot.testing.entity.Repairability getRepairability() {
        return repairability;
    }

    public void setRepairability(com.repopilot.testing.entity.Repairability repairability) {
        this.repairability = repairability;
    }

    public com.repopilot.testing.entity.ConfidenceLevel getConfidence() {
        return confidence;
    }

    public void setConfidence(com.repopilot.testing.entity.ConfidenceLevel confidence) {
        this.confidence = confidence;
    }

    public String getClassificationReason() {
        return classificationReason;
    }

    public void setClassificationReason(String classificationReason) {
        this.classificationReason = classificationReason;
    }

    public String getRepairBlockReason() {
        return repairBlockReason;
    }

    public void setRepairBlockReason(String repairBlockReason) {
        this.repairBlockReason = repairBlockReason;
    }

    public String getClassifierVersion() {
        return classifierVersion;
    }

    public void setClassifierVersion(String classifierVersion) {
        this.classifierVersion = classifierVersion;
    }

    public Instant getClassifiedAt() {
        return classifiedAt;
    }

    public void setClassifiedAt(Instant classifiedAt) {
        this.classifiedAt = classifiedAt;
    }

    public Boolean getIsRelatedToCodeChanges() {
        return isRelatedToCodeChanges;
    }

    public void setIsRelatedToCodeChanges(Boolean isRelatedToCodeChanges) {
        this.isRelatedToCodeChanges = isRelatedToCodeChanges;
    }

    public Object getClassificationEvidence() {
        return classificationEvidence;
    }

    public void setClassificationEvidence(Object classificationEvidence) {
        this.classificationEvidence = classificationEvidence;
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
