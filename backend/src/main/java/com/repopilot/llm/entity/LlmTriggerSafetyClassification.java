package com.repopilot.llm.entity;

public enum LlmTriggerSafetyClassification {
    CONFIRMED_NOT_DISPATCHED,
    CONFIRMED_PROVIDER_REJECTION,
    SUCCEEDED,
    UNKNOWN_EXTERNAL_OUTCOME,
    STALE_INTERNAL_RESULT
}
