package com.repopilot.review.transport;

import com.repopilot.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Bounded exception class for AI Reviewer transport errors.
 * Ensures internal network details, provider API keys, and raw credentials are never leaked.
 */
public class AiReviewerTransportException extends ApiException {

    private final AiReviewerFailureCategory category;

    public AiReviewerTransportException(HttpStatus status, AiReviewerFailureCategory category, String detail) {
        super(status, category.getCode(), sanitizeDetail(category, detail));
        this.category = category;
    }

    public AiReviewerFailureCategory getCategory() {
        return category;
    }

    public static AiReviewerTransportException timeout(String detail) {
        return new AiReviewerTransportException(HttpStatus.GATEWAY_TIMEOUT, AiReviewerFailureCategory.TIMEOUT, detail);
    }

    public static AiReviewerTransportException unavailable(String detail) {
        return new AiReviewerTransportException(HttpStatus.SERVICE_UNAVAILABLE, AiReviewerFailureCategory.PROVIDER_UNAVAILABLE, detail);
    }

    public static AiReviewerTransportException malformedResponse(String detail) {
        return new AiReviewerTransportException(HttpStatus.BAD_GATEWAY, AiReviewerFailureCategory.MALFORMED_RESPONSE, detail);
    }

    public static AiReviewerTransportException transportFailure(String detail) {
        return new AiReviewerTransportException(HttpStatus.BAD_GATEWAY, AiReviewerFailureCategory.TRANSPORT_FAILURE, detail);
    }

    public static AiReviewerTransportException validationFailure(String detail) {
        return new AiReviewerTransportException(HttpStatus.BAD_REQUEST, AiReviewerFailureCategory.VALIDATION_FAILURE, detail);
    }

    private static String sanitizeDetail(AiReviewerFailureCategory category, String detail) {
        if (detail == null || detail.isBlank()) {
            return category.getDescription();
        }
        // Ensure failure details are bounded and free of stack traces or sensitive header info
        String cleaned = detail;
        cleaned = cleaned.replaceAll("(?i)Bearer\\s+[a-zA-Z0-9_\\.~+\\-\\/=]+", "[REDACTED_HEADER]");
        cleaned = cleaned.replaceAll("(?i)(?:authorization|api_key|token|password|key)[:=\\s]+(?:Bearer\\s+)?\\S+", "[REDACTED_HEADER]");
        if (cleaned.length() > 500) {
            cleaned = cleaned.substring(0, 500) + "...";
        }
        return category.getDescription() + ": " + cleaned;
    }
}
