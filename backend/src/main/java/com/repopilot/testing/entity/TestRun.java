package com.repopilot.testing.entity;

import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_runs")
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id")
    private TaskExecution taskExecution;

    @Column(name = "execution_number", nullable = false)
    private Integer executionNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column
    private Ecosystem ecosystem;

    @Column(name = "test_target", length = 255)
    private String testTarget;

    @Column(columnDefinition = "TEXT")
    private String command;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String environment;

    @Column(nullable = false)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type")
    private FailureType failureType;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "container_id", length = 100)
    private String containerId;

    @Column(name = "stdout_summary", columnDefinition = "TEXT")
    private String stdoutSummary;

    @Column(name = "stderr_summary", columnDefinition = "TEXT")
    private String stderrSummary;

    @Column(name = "stdout_truncated")
    private Boolean stdoutTruncated = false;

    @Column(name = "stderr_truncated")
    private Boolean stderrTruncated = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "termination_reason")
    private TerminationReason terminationReason = TerminationReason.NORMAL_EXIT;

    @Column(name = "stdout_bytes_observed")
    private Long stdoutBytesObserved = 0L;

    @Column(name = "stderr_bytes_observed")
    private Long stderrBytesObserved = 0L;

    @Column(name = "tests_run")
    private Integer testsRun = 0;

    @Column(name = "failures_count")
    private Integer failuresCount = 0;

    @Column(name = "errors_count")
    private Integer errorsCount = 0;

    @Column(name = "skipped_count")
    private Integer skippedCount = 0;

    @Column(name = "passed_count")
    private Integer passedCount = 0;

    @Column(name = "parser_status")
    private String parserStatus = "UNPARSED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "failure_evidence", columnDefinition = "jsonb")
    private String failureEvidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_category")
    private FailureType failureCategory = FailureType.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(name = "repairability")
    private Repairability repairability = Repairability.AMBIGUOUS;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence")
    private ConfidenceLevel confidence = ConfidenceLevel.LOW;

    @Column(name = "classification_reason", columnDefinition = "TEXT")
    private String classificationReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "classification_evidence", columnDefinition = "jsonb")
    private String classificationEvidence;

    @Column(name = "classified_at")
    private Instant classifiedAt;

    @Column(name = "classifier_version", length = 50)
    private String classifierVersion = "10.3-v1";

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public TestRun() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public TaskExecution getTaskExecution() {
        return taskExecution;
    }

    public void setTaskExecution(TaskExecution taskExecution) {
        this.taskExecution = taskExecution;
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

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
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

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
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

    public TerminationReason getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(TerminationReason terminationReason) {
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

    public String getFailureEvidence() {
        return failureEvidence;
    }

    public void setFailureEvidence(String failureEvidence) {
        this.failureEvidence = failureEvidence;
    }

    public FailureType getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(FailureType failureCategory) {
        this.failureCategory = failureCategory;
    }

    public Repairability getRepairability() {
        return repairability;
    }

    public void setRepairability(Repairability repairability) {
        this.repairability = repairability;
    }

    public ConfidenceLevel getConfidence() {
        return confidence;
    }

    public void setConfidence(ConfidenceLevel confidence) {
        this.confidence = confidence;
    }

    public String getClassificationReason() {
        return classificationReason;
    }

    public void setClassificationReason(String classificationReason) {
        this.classificationReason = classificationReason;
    }

    public String getClassificationEvidence() {
        return classificationEvidence;
    }

    public void setClassificationEvidence(String classificationEvidence) {
        this.classificationEvidence = classificationEvidence;
    }

    public Instant getClassifiedAt() {
        return classifiedAt;
    }

    public void setClassifiedAt(Instant classifiedAt) {
        this.classifiedAt = classifiedAt;
    }

    public String getClassifierVersion() {
        return classifierVersion;
    }

    public void setClassifierVersion(String classifierVersion) {
        this.classifierVersion = classifierVersion;
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
