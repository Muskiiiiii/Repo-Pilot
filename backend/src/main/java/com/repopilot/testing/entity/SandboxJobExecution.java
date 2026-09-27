package com.repopilot.testing.entity;

import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.TaskExecution;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sandbox_job_executions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_sandbox_job_task_exec", columnNames = {"task_execution_id", "command_type"})
})
public class SandboxJobExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_execution_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TaskExecution taskExecution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_worker_node_id")
    private SandboxWorkerNode assignedWorkerNode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SandboxJobStatus status = SandboxJobStatus.PENDING;

    @Column(name = "lease_token", nullable = false)
    private UUID leaseToken = UUID.randomUUID();

    @Column(name = "fencing_version", nullable = false)
    private Long fencingVersion = 1L;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 1;

    @Column(name = "command_type", nullable = false, length = 50)
    private String commandType;

    @Column(name = "command_args", nullable = false, columnDefinition = "TEXT")
    private String commandArgs;

    @Column(name = "container_id", length = 128)
    private String containerId;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "cpu_limit_cores", nullable = false)
    private Double cpuLimitCores = 2.0;

    @Column(name = "memory_limit_mb", nullable = false)
    private Integer memoryLimitMb = 2048;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds = 120;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "started_at")
    private Instant startedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public SandboxJobExecution() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public TaskExecution getTaskExecution() {
        return taskExecution;
    }

    public void setTaskExecution(TaskExecution taskExecution) {
        this.taskExecution = taskExecution;
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

    public SandboxWorkerNode getAssignedWorkerNode() {
        return assignedWorkerNode;
    }

    public void setAssignedWorkerNode(SandboxWorkerNode assignedWorkerNode) {
        this.assignedWorkerNode = assignedWorkerNode;
    }

    public SandboxJobStatus getStatus() {
        return status;
    }

    public void setStatus(SandboxJobStatus status) {
        this.status = status;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(UUID leaseToken) {
        this.leaseToken = leaseToken;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public String getCommandArgs() {
        return commandArgs;
    }

    public void setCommandArgs(String commandArgs) {
        this.commandArgs = commandArgs;
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public Double getCpuLimitCores() {
        return cpuLimitCores;
    }

    public void setCpuLimitCores(Double cpuLimitCores) {
        this.cpuLimitCores = cpuLimitCores;
    }

    public Integer getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public void setMemoryLimitMb(Integer memoryLimitMb) {
        this.memoryLimitMb = memoryLimitMb;
    }

    public Integer getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
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

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
