package com.repopilot.review.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ValidationResult {

    private final boolean valid;
    private final ValidationErrorReason reason;
    private final String safeErrorMessage;
    private final List<ValidatedFindingCandidate> validatedFindings;
    private final List<String> rejectedFindingErrors;

    public ValidationResult(boolean valid, ValidationErrorReason reason, String safeErrorMessage,
                            List<ValidatedFindingCandidate> validatedFindings, List<String> rejectedFindingErrors) {
        this.valid = valid;
        this.reason = reason != null ? reason : ValidationErrorReason.NONE;
        this.safeErrorMessage = safeErrorMessage;
        this.validatedFindings = validatedFindings != null ? Collections.unmodifiableList(validatedFindings) : List.of();
        this.rejectedFindingErrors = rejectedFindingErrors != null ? Collections.unmodifiableList(rejectedFindingErrors) : List.of();
    }

    public static ValidationResult success(List<ValidatedFindingCandidate> findings) {
        return new ValidationResult(true, ValidationErrorReason.NONE, null, findings, List.of());
    }

    public static ValidationResult failure(ValidationErrorReason reason, String safeErrorMessage) {
        return new ValidationResult(false, reason, safeErrorMessage, List.of(), List.of(safeErrorMessage));
    }

    public static ValidationResult partial(List<ValidatedFindingCandidate> validFindings, List<String> rejectedErrors) {
        boolean isValid = validFindings != null && !validFindings.isEmpty();
        ValidationErrorReason reason = isValid ? ValidationErrorReason.NONE : ValidationErrorReason.STRUCTURAL_VALIDATION_FAILURE;
        return new ValidationResult(isValid, reason, null, validFindings, rejectedErrors);
    }

    public boolean isValid() {
        return valid;
    }

    public ValidationErrorReason getReason() {
        return reason;
    }

    public String getSafeErrorMessage() {
        return safeErrorMessage;
    }

    public List<ValidatedFindingCandidate> getValidatedFindings() {
        return validatedFindings;
    }

    public List<String> getRejectedFindingErrors() {
        return rejectedFindingErrors;
    }
}
