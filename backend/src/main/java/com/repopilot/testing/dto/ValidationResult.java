package com.repopilot.testing.dto;

public class ValidationResult {

    private final boolean valid;
    private final String reason;
    private final String errorCode;

    public ValidationResult(boolean valid, String reason, String errorCode) {
        this.valid = valid;
        this.reason = reason;
        this.errorCode = errorCode;
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, "Validation passed successfully", "VALIDATED");
    }

    public static ValidationResult invalid(String errorCode, String reason) {
        return new ValidationResult(false, reason, errorCode);
    }

    public boolean isValid() {
        return valid;
    }

    public String getReason() {
        return reason;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
