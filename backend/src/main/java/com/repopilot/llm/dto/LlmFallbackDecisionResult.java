package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmFallbackDecision;
import com.repopilot.llm.entity.LlmProviderAttempt;

public class LlmFallbackDecisionResult {

    private final boolean success;
    private final String rejectionReason;
    private final LlmFallbackDecision fallbackDecision;
    private final LlmProviderAttempt targetAttempt;

    private LlmFallbackDecisionResult(boolean success, String rejectionReason, LlmFallbackDecision fallbackDecision, LlmProviderAttempt targetAttempt) {
        this.success = success;
        this.rejectionReason = rejectionReason;
        this.fallbackDecision = fallbackDecision;
        this.targetAttempt = targetAttempt;
    }

    public static LlmFallbackDecisionResult success(LlmFallbackDecision decision, LlmProviderAttempt targetAttempt) {
        return new LlmFallbackDecisionResult(true, null, decision, targetAttempt);
    }

    public static LlmFallbackDecisionResult rejected(String rejectionReason, LlmFallbackDecision decision) {
        return new LlmFallbackDecisionResult(false, rejectionReason, decision, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public LlmFallbackDecision getFallbackDecision() {
        return fallbackDecision;
    }

    public LlmProviderAttempt getTargetAttempt() {
        return targetAttempt;
    }
}
