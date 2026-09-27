package com.repopilot.testing.entity;

public enum RepairIterationStatus {
    CREATED,
    ELIGIBILITY_CHECKED,
    DIAGNOSING,
    DIAGNOSED,
    REPAIR_GENERATING,
    REPAIR_GENERATED,
    VALIDATING,
    VALIDATED,
    EXECUTING,
    COMPLETED,
    VERIFIED,
    REJECTED,
    CANCELLED,
    TIMED_OUT,
    AI_FAILURE,
    VALIDATION_FAILED,
    EXECUTION_FAILED,
    NOT_REPAIRABLE,
    MAX_ITERATIONS,
    MAX_TIME;

    public boolean isTerminal() {
        return this == VERIFIED ||
               this == REJECTED ||
               this == CANCELLED ||
               this == TIMED_OUT ||
               this == AI_FAILURE ||
               this == VALIDATION_FAILED ||
               this == EXECUTION_FAILED ||
               this == NOT_REPAIRABLE ||
               this == MAX_ITERATIONS ||
               this == MAX_TIME;
    }
}
