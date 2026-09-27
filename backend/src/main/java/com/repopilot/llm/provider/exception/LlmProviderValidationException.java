package com.repopilot.llm.provider.exception;

import com.repopilot.llm.provider.model.LlmProviderFailureReason;

public class LlmProviderValidationException extends LlmProviderException {

    public LlmProviderValidationException(String message) {
        super(LlmProviderFailureReason.MALFORMED_PROVIDER_RESPONSE, message);
    }
}
