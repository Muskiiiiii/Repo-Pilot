package com.repopilot.testing.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RepairLoopStatusResponseDto {

    private UUID executionId;
    private UUID taskId;
    private String executionStatus;
    private String repairStatus;
    private int iterationsRun;
    private int maxIterations;
    private String latestDecision;
    private String outcomePolicy;
    private String terminationReason;
    private List<RepairIterationSummaryDto> iterations;
    private Instant startedAt;
    private Instant completedAt;

    public RepairLoopStatusResponseDto() {
        this.maxIterations = 3;
        this.iterations = new ArrayList<>();
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

    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(String executionStatus) {
        this.executionStatus = executionStatus;
    }

    public String getRepairStatus() {
        return repairStatus;
    }

    public void setRepairStatus(String repairStatus) {
        this.repairStatus = repairStatus;
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

    public String getLatestDecision() {
        return latestDecision;
    }

    public void setLatestDecision(String latestDecision) {
        this.latestDecision = latestDecision;
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

    public List<RepairIterationSummaryDto> getIterations() {
        return iterations;
    }

    public void setIterations(List<RepairIterationSummaryDto> iterations) {
        this.iterations = iterations;
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
