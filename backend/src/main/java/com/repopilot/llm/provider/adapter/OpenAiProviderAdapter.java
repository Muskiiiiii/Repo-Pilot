package com.repopilot.llm.provider.adapter;

import com.repopilot.llm.provider.LlmProviderClient;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.provider.transport.OpenAiTransport;
import com.repopilot.llm.provider.validation.LlmResponseValidator;

import java.util.Objects;
import java.util.Set;

public class OpenAiProviderAdapter implements LlmProviderClient {

    private final String providerId = "openai";
    private final OpenAiTransport transport;

    public OpenAiProviderAdapter(OpenAiTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    @Override
    public String getProviderId() {
        return providerId;
    }

    @Override
    public Set<LlmProviderCapability> getCapabilities() {
        return LlmProviderCapabilityRegistry.getCapabilities(providerId);
    }

    @Override
    public LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException {
        // 1. Validate Provider and Model against Capability Registry
        LlmProviderCapabilityRegistry.validateProviderAndModel(request.getProviderId(), request.getModelId());

        // 2. Delegate to Transport Layer
        LlmProviderResponse rawResponse = transport.execute(request);

        // 3. Normalize & Validate via Response Validator
        return LlmResponseValidator.validate(request, rawResponse);
    }
}
