package com.repopilot.report.entity;

import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "implementation_reports", uniqueConstraints = {
    @UniqueConstraint(name = "uk_impl_reports_execution_id", columnNames = {"task_execution_id"})
})
public class ImplementationReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_execution_id", nullable = false, unique = true)
    private TaskExecution taskExecution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReportStatus status = ReportStatus.GENERATED;

    @Column(name = "execution_outcome", nullable = false, length = 50)
    private String executionOutcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_files_summary", columnDefinition = "jsonb")
    private String changedFilesSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "test_summary", columnDefinition = "jsonb")
    private String testSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "security_review_summary", columnDefinition = "jsonb")
    private String securityReviewSummary;

    @Column(name = "confidence_score", precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    @Column(name = "source_context_hash", nullable = false, length = 64, updatable = false)
    private String sourceContextHash;

    @Column(name = "correlation_id", nullable = false, length = 64, updatable = false)
    private String correlationId;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ImplementationReport() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
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
