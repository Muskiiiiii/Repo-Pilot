package com.repopilot.testing.dto;

import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.testing.entity.Ecosystem;

import java.time.Instant;
import java.util.UUID;

public class TaskExecutionResponseDto {

    private UUID executionId;
    private UUID taskId;
    private UUID repositoryId;
    private UUID projectId;
    private Integer executionNumber;
    private ExecutionStatus status;
    private Ecosystem ecosystem;
    private Boolean cancellationRequested;
    private Instant startedAt;
    private Instant completedAt;
    private Instant cleanedAt;
    private String errorMessage;
    private TestRunResponseDto testRun;

    public TaskExecutionResponseDto() {
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public void setExecutionId(UUID executionId) {
        this.executionId = executionId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public void setRepositoryId(UUID repositoryId) {
        this.repositoryId = repositoryId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
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

    public Ecosystem getEcosystem() {
        return ecosystem;
    }

    public void setEcosystem(Ecosystem ecosystem) {
        this.ecosystem = ecosystem;
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

    public TestRunResponseDto getTestRun() {
        return testRun;
    }

    public void setTestRun(TestRunResponseDto testRun) {
        this.testRun = testRun;
    }
}
