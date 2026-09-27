package com.repopilot.testing.dto;

import java.time.Instant;
import java.util.UUID;

public class RepairLoopResponseDto {

    private UUID executionId;
    private UUID taskId;
    private String status;
    private int iterationsRun;
    private int maxIterations;
    private String outcomePolicy;
    private String terminationReason;
    private boolean alreadyCompleted;
    private Instant startedAt;
    private Instant completedAt;

    public RepairLoopResponseDto() {
        this.maxIterations = 3;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getIterationsRun() {
        return iterationsRun;
    }

    public void setIterationsRun(int iterationsRun) {
        this.iterationsRun = iterationsRun;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public String getOutcomePolicy() {
        return outcomePolicy;
    }

    public void setOutcomePolicy(String outcomePolicy) {
        this.outcomePolicy = outcomePolicy;
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
        this.terminationReason = terminationReason;
    }

    public boolean isAlreadyCompleted() {
        return alreadyCompleted;
    }

    public void setAlreadyCompleted(boolean alreadyCompleted) {
        this.alreadyCompleted = alreadyCompleted;
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
