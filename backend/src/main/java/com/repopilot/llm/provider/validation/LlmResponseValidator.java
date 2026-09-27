package com.repopilot.llm.provider.validation;

import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderValidationException;

public final class LlmResponseValidator {

    private static final int MAX_SANITY_TOKENS = 2_000_000;

    private LlmResponseValidator() {
    }

    public static LlmProviderResponse validate(LlmProviderRequest request, LlmProviderResponse response) {
        if (response == null) {
            throw new LlmProviderValidationException("Provider response cannot be null");
        }

        // 1. Identity Verification
        if (!request.getProviderId().equalsIgnoreCase(response.getProviderId())) {
            throw new LlmProviderValidationException("Provider ID mismatch: expected " + request.getProviderId() + " but got " + response.getProviderId());
        }
        if (!request.getModelId().equalsIgnoreCase(response.getModelId())) {
            throw new LlmProviderValidationException("Model ID mismatch: expected " + request.getModelId() + " but got " + response.getModelId());
        }

        // 2. Provider Request ID Check
        if (response.getProviderRequestId() == null || response.getProviderRequestId().trim().isEmpty()) {
            throw new LlmProviderValidationException("Missing or empty providerRequestId in provider response");
        }
        if (!response.getProviderRequestId().matches("[a-zA-Z0-9_\\./:-]+")) {
            throw new LlmProviderValidationException("Invalid providerRequestId format: " + response.getProviderRequestId());
        }

        // 3. Token Boundary Validation
        if (response.getPromptTokens() < 0) {
            throw new LlmProviderValidationException("Prompt tokens cannot be negative: " + response.getPromptTokens());
        }
        if (response.getCompletionTokens() < 0) {
            throw new LlmProviderValidationException("Completion tokens cannot be negative: " + response.getCompletionTokens());
        }
        if (response.getTotalTokens() != response.getPromptTokens() + response.getCompletionTokens()) {
            throw new LlmProviderValidationException("Total tokens (" + response.getTotalTokens() + ") does not equal prompt (" + response.getPromptTokens() + ") + completion (" + response.getCompletionTokens() + ")");
        }
        if (response.getTotalTokens() > MAX_SANITY_TOKENS) {
            throw new LlmProviderValidationException("Token count exceeds sanity ceiling: " + response.getTotalTokens() + " > " + MAX_SANITY_TOKENS);
        }

        // 4. Cost Sanity Validation
        if (response.getProviderCalculatedCostMicroUsd() != null && response.getProviderCalculatedCostMicroUsd() < 0) {
            throw new LlmProviderValidationException("Provider calculated cost cannot be negative: " + response.getProviderCalculatedCostMicroUsd());
        }

        return response;
    }
}
