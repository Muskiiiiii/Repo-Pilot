package com.repopilot.llm.provider.exception;

import com.repopilot.llm.provider.model.LlmProviderFailureReason;

public class LlmUnsupportedCapabilityException extends LlmProviderException {

    public LlmUnsupportedCapabilityException(String message) {
        super(LlmProviderFailureReason.UNSUPPORTED_CAPABILITY, message);
    }
}
