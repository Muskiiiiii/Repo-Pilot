package com.repopilot.testing.dto;

import com.repopilot.testing.policy.D3DecisionType;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MultiIterationOrchestrationResponseDto {

    private UUID taskExecutionId;
    private int totalIterationsExecuted;
    private int totalExecutions;
    private D3DecisionType finalDecision;
    private String finalOutcomePolicy;
    private String finalReason;
    private long totalDurationMs;
    private List<UUID> iterationIds;
    private Instant completedAt;

    public MultiIterationOrchestrationResponseDto() {
    }

    public MultiIterationOrchestrationResponseDto(UUID taskExecutionId,
                                                 int totalIterationsExecuted,
                                                 int totalExecutions,
                                                 D3DecisionType finalDecision,
                                                 String finalOutcomePolicy,
                                                 String finalReason,
                                                 long totalDurationMs,
                                                 List<UUID> iterationIds,
                                                 Instant completedAt) {
        this.taskExecutionId = taskExecutionId;
        this.totalIterationsExecuted = totalIterationsExecuted;
        this.totalExecutions = totalExecutions;
        this.finalDecision = finalDecision;
        this.finalOutcomePolicy = finalOutcomePolicy;
        this.finalReason = finalReason;
        this.totalDurationMs = totalDurationMs;
        this.iterationIds = iterationIds != null ? List.copyOf(iterationIds) : Collections.emptyList();
        this.completedAt = completedAt != null ? completedAt : Instant.now();
    }

    public UUID getTaskExecutionId() { return taskExecutionId; }
    public void setTaskExecutionId(UUID taskExecutionId) { this.taskExecutionId = taskExecutionId; }

    public int getTotalIterationsExecuted() { return totalIterationsExecuted; }
    public int getIterationsRun() { return totalIterationsExecuted; }
    public void setTotalIterationsExecuted(int totalIterationsExecuted) { this.totalIterationsExecuted = totalIterationsExecuted; }

    public int getTotalExecutions() { return totalExecutions; }
    public void setTotalExecutions(int totalExecutions) { this.totalExecutions = totalExecutions; }

    public D3DecisionType getFinalDecision() { return finalDecision; }
    public void setFinalDecision(D3DecisionType finalDecision) { this.finalDecision = finalDecision; }

    public String getFinalOutcomePolicy() { return finalOutcomePolicy; }
    public String getOutcomePolicy() { return finalOutcomePolicy; }
    public void setFinalOutcomePolicy(String finalOutcomePolicy) { this.finalOutcomePolicy = finalOutcomePolicy; }

    public String getFinalReason() { return finalReason; }
    public String getDecisionReason() { return finalReason; }
    public void setFinalReason(String finalReason) { this.finalReason = finalReason; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public long getExecutionDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public List<UUID> getIterationIds() { return iterationIds; }
    public List<UUID> getExecutedIterationIds() { return iterationIds; }
    public void setIterationIds(List<UUID> iterationIds) { this.iterationIds = iterationIds != null ? List.copyOf(iterationIds) : Collections.emptyList(); }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
