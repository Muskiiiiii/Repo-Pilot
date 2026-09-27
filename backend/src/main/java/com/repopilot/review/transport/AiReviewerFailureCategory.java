package com.repopilot.review.transport;

/**
 * Bounded failure categories for AI Reviewer transport errors.
 */
public enum AiReviewerFailureCategory {
    TIMEOUT("AI_REVIEWER_TIMEOUT", "AI reviewer transport request timed out"),
    PROVIDER_UNAVAILABLE("AI_REVIEWER_UNAVAILABLE", "AI reviewer service is unavailable"),
    MALFORMED_RESPONSE("AI_REVIEWER_MALFORMED_RESPONSE", "AI reviewer response was malformed or invalid"),
    TRANSPORT_FAILURE("AI_REVIEWER_TRANSPORT_FAILURE", "AI reviewer transport failed"),
    VALIDATION_FAILURE("AI_REVIEWER_VALIDATION_FAILURE", "AI reviewer response failed contract validation");

    private final String code;
    private final String description;

    AiReviewerFailureCategory(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
