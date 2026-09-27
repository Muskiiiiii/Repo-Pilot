package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmFallbackDecision;
import com.repopilot.llm.entity.LlmProviderAttempt;

public class LlmReconciliationResult {

    private final boolean resolved;
    private final String status;
    private final String detail;
    private final LlmProviderAttempt targetAttempt;
    private final LlmFallbackDecision decision;

    public LlmReconciliationResult(boolean resolved, String status, String detail, LlmProviderAttempt targetAttempt, LlmFallbackDecision decision) {
        this.resolved = resolved;
        this.status = status;
        this.detail = detail;
        this.targetAttempt = targetAttempt;
        this.decision = decision;
    }

    public static LlmReconciliationResult confirmedZeroUsage(LlmProviderAttempt targetAttempt, LlmFallbackDecision decision) {
        return new LlmReconciliationResult(true, "CONFIRMED_ZERO_USAGE", "External operation confirmed not executed or failed with zero charge", targetAttempt, decision);
    }

    public static LlmReconciliationResult confirmedActualUsage(LlmProviderAttempt targetAttempt, LlmFallbackDecision decision) {
        return new LlmReconciliationResult(true, "CONFIRMED_ACTUAL_USAGE", "External operation confirmed executed successfully", targetAttempt, decision);
    }

    public static LlmReconciliationResult remainsUnknown(LlmProviderAttempt targetAttempt, LlmFallbackDecision decision) {
        return new LlmReconciliationResult(false, "REMAINS_UNKNOWN", "External outcome remains ambiguous", targetAttempt, decision);
    }

    public static LlmReconciliationResult unresolvedExposurePending(LlmProviderAttempt targetAttempt, LlmFallbackDecision decision) {
        return new LlmReconciliationResult(false, "UNRESOLVED_EXPOSURE_PENDING", "Unresolved exposure pending authoritative evidence", targetAttempt, decision);
    }

    public static LlmReconciliationResult unsupportedCapability(LlmProviderAttempt targetAttempt, LlmFallbackDecision decision) {
        return new LlmReconciliationResult(false, "UNSUPPORTED_RECONCILIATION_CAPABILITY", "Provider does not support authoritative execution reconciliation", targetAttempt, decision);
    }

    public boolean isResolved() {
        return resolved;
    }

    public String getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public LlmProviderAttempt getTargetAttempt() {
        return targetAttempt;
    }

    public LlmFallbackDecision getDecision() {
        return decision;
    }
}
