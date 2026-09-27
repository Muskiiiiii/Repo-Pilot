package com.repopilot.llm.entity;

import com.repopilot.llm.dto.LlmExecutionOutcome.SafetyClassification;

public enum LlmExecutionStatus {
    ATTEMPT_CREATED,
    EXECUTION_CLAIMED,
    EXECUTING,
    SUCCEEDED,
    SUCCESS,
    FAILED,
    TIMED_OUT,
    TIMEOUT,
    REJECTED_SECURITY,
    REJECTED_POLICY,
    TRANSPORT_ERROR,
    RATE_LIMITED,
    MALFORMED_OUTPUT,
    VALIDATION_FAILED,
    FAILED_EXHAUSTED;

    /**
     * Maps persistence execution status to fallback safety classification.
     */
    public SafetyClassification toSafetyClassification() {
        if (this == SUCCEEDED || this == SUCCESS) {
            return SafetyClassification.SUCCEEDED;
        }
        if (this == TIMED_OUT || this == TIMEOUT) {
            return SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME;
        }
        return SafetyClassification.CONFIRMED_PROVIDER_REJECTION;
    }
}
