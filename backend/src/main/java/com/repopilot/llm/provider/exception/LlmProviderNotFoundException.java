package com.repopilot.llm.provider.exception;

import com.repopilot.llm.provider.model.LlmProviderFailureReason;

public class LlmProviderNotFoundException extends LlmProviderException {

    public LlmProviderNotFoundException(String providerId) {
        super(LlmProviderFailureReason.UNSUPPORTED_CAPABILITY, "Provider not found in registry: " + sanitizeMessage(providerId));
    }
}
