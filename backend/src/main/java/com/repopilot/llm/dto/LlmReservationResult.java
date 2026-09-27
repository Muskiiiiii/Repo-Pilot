package com.repopilot.llm.dto;

import com.repopilot.llm.entity.LlmProviderAttempt;

public class LlmReservationResult {

    private final boolean success;
    private final String rejectionReason;
    private final int tokens;
    private final long spendMicroUsd;
    private final LlmProviderAttempt attempt;

    public LlmReservationResult(boolean success, String rejectionReason, int tokens, long spendMicroUsd, LlmProviderAttempt attempt) {
        this.success = success;
        this.rejectionReason = rejectionReason;
        this.tokens = tokens;
        this.spendMicroUsd = spendMicroUsd;
        this.attempt = attempt;
    }

    public static LlmReservationResult success(int tokens, long spendMicroUsd, LlmProviderAttempt attempt) {
        return new LlmReservationResult(true, null, tokens, spendMicroUsd, attempt);
    }

    public static LlmReservationResult rejected(String reason, LlmProviderAttempt attempt) {
        return new LlmReservationResult(false, reason, 0, 0L, attempt);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public int getTokens() {
        return tokens;
    }

    public long getSpendMicroUsd() {
        return spendMicroUsd;
    }

    public LlmProviderAttempt getAttempt() {
        return attempt;
    }
}
