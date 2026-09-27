package com.repopilot.task.entity;

import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.testing.entity.Ecosystem;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_executions")
public class TaskExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id")
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(name = "execution_number", nullable = false)
    private Integer executionNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status = ExecutionStatus.CREATED;

    @Column(name = "execution_token", length = 64)
    private String executionToken;

    @Enumerated(EnumType.STRING)
    @Column
    private Ecosystem ecosystem;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "code_change_snapshot", columnDefinition = "jsonb")
    private String codeChangeSnapshot;

    @Column(name = "cancellation_requested")
    private Boolean cancellationRequested = false;

    @Column(name = "fencing_epoch", nullable = false)
    private Long fencingEpoch = 0L;

    @Column(name = "owner_token", length = 64)
    private String ownerToken;

    @Column(name = "repairs_consumed", nullable = false)
    private Integer repairsConsumed = 0;

    @Column(name = "recovery_attempts", nullable = false)
    private Integer recoveryAttempts = 0;

    @Version
    @Column(nullable = false)
    private Integer version = 0;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cleaned_at")
    private Instant cleanedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    public TaskExecution() {
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

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Integer getExecutionNumber() {
        return executionNumber;
    }

    public void setExecutionNumber(Integer executionNumber) {
        this.executionNumber = executionNumber;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public String getExecutionToken() {
        return executionToken;
    }

    public void setExecutionToken(String executionToken) {
        this.executionToken = executionToken;
    }

    public Ecosystem getEcosystem() {
        return ecosystem;
    }

    public void setEcosystem(Ecosystem ecosystem) {
        this.ecosystem = ecosystem;
    }

    public String getCodeChangeSnapshot() {
        return codeChangeSnapshot;
    }

    public void setCodeChangeSnapshot(String codeChangeSnapshot) {
        this.codeChangeSnapshot = codeChangeSnapshot;
    }

    public Boolean getCancellationRequested() {
        return cancellationRequested;
    }

    public void setCancellationRequested(Boolean cancellationRequested) {
        this.cancellationRequested = cancellationRequested;
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

    public Instant getCleanedAt() {
        return cleanedAt;
    }

    public void setCleanedAt(Instant cleanedAt) {
        this.cleanedAt = cleanedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getFencingEpoch() {
        return fencingEpoch;
    }

    public void setFencingEpoch(Long fencingEpoch) {
        this.fencingEpoch = fencingEpoch;
    }

    public String getOwnerToken() {
        return ownerToken;
    }

    public void setOwnerToken(String ownerToken) {
        this.ownerToken = ownerToken;
    }

    public Integer getRepairsConsumed() {
        return repairsConsumed;
    }

    public void setRepairsConsumed(Integer repairsConsumed) {
        this.repairsConsumed = repairsConsumed;
    }

    public Integer getRecoveryAttempts() {
        return recoveryAttempts;
    }

    public void setRecoveryAttempts(Integer recoveryAttempts) {
        this.recoveryAttempts = recoveryAttempts;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
