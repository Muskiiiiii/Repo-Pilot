package com.repopilot.llm.provider.exception;

import com.repopilot.llm.provider.model.LlmProviderFailureReason;

public class LlmProviderException extends RuntimeException {

    private final LlmProviderFailureReason failureReason;

    public LlmProviderException(LlmProviderFailureReason failureReason, String message) {
        super(sanitizeMessage(message));
        this.failureReason = failureReason;
    }

    public LlmProviderException(LlmProviderFailureReason failureReason, String message, Throwable cause) {
        super(sanitizeMessage(message), cause);
        this.failureReason = failureReason;
    }

    public LlmProviderFailureReason getFailureReason() {
        return failureReason;
    }

    public static String sanitizeMessage(String input) {
        if (input == null) return "";
        return input.replaceAll("(sk-[a-zA-Z0-9_-]{20,})", "sk-***[REDACTED]***")
                    .replaceAll("(gsv-[a-zA-Z0-9_-]{20,})", "gsv-***[REDACTED]***")
                    .replaceAll("(Bearer\\s+)[a-zA-Z0-9_\\.-]+", "$1***[REDACTED]***");
    }
}
