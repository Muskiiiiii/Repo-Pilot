package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmFallbackDecision;
import com.repopilot.llm.entity.LlmProviderAttempt;

public class LlmFallbackExecutionResult {

    private final boolean success;
    private final String status;
    private final String rejectionReason;
    private final LlmFallbackDecision decision;
    private final LlmProviderAttempt targetAttempt;
    private final LlmExecutionOutcome executionOutcome;

    public LlmFallbackExecutionResult(boolean success,
                                      String status,
                                      String rejectionReason,
                                      LlmFallbackDecision decision,
                                      LlmProviderAttempt targetAttempt,
                                      LlmExecutionOutcome executionOutcome) {
        this.success = success;
        this.status = status;
        this.rejectionReason = rejectionReason;
        this.decision = decision;
        this.targetAttempt = targetAttempt;
        this.executionOutcome = executionOutcome;
    }

    public static LlmFallbackExecutionResult success(LlmFallbackDecision decision, LlmProviderAttempt targetAttempt, LlmExecutionOutcome outcome) {
        return new LlmFallbackExecutionResult(true, "COMPLETED_SUCCESS", null, decision, targetAttempt, outcome);
    }

    public static LlmFallbackExecutionResult failed(String reason, LlmFallbackDecision decision, LlmProviderAttempt targetAttempt, LlmExecutionOutcome outcome) {
        return new LlmFallbackExecutionResult(false, "COMPLETED_FAILED", reason, decision, targetAttempt, outcome);
    }

    public static LlmFallbackExecutionResult blocked(String rejectionReason, LlmFallbackDecision decision) {
        return new LlmFallbackExecutionResult(false, "BLOCKED_POLICY", rejectionReason, decision, null, null);
    }

    public static LlmFallbackExecutionResult reconciliationRequired(LlmFallbackDecision decision, LlmProviderAttempt targetAttempt, LlmExecutionOutcome outcome) {
        return new LlmFallbackExecutionResult(false, "RECONCILIATION_REQUIRED", "UNKNOWN_EXTERNAL_OUTCOME", decision, targetAttempt, outcome);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public LlmFallbackDecision getDecision() {
        return decision;
    }

    public LlmProviderAttempt getTargetAttempt() {
        return targetAttempt;
    }

    public LlmExecutionOutcome getExecutionOutcome() {
        return executionOutcome;
    }
}
