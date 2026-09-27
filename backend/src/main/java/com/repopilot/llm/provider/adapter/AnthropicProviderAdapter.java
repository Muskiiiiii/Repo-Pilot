package com.repopilot.llm.provider.adapter;

import com.repopilot.llm.provider.LlmProviderClient;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.exception.LlmUnsupportedCapabilityException;
import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.provider.transport.AnthropicTransport;
import com.repopilot.llm.provider.validation.LlmResponseValidator;

import java.util.Objects;
import java.util.Set;

public class AnthropicProviderAdapter implements LlmProviderClient {

    private final String providerId = "anthropic";
    private final AnthropicTransport transport;

    public AnthropicProviderAdapter(AnthropicTransport transport) {
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

        // 2. Validate Idempotency Capability: Anthropic does NOT support idempotency keys
        if (request.getProviderIdempotencyKey() != null && !request.getProviderIdempotencyKey().isEmpty()) {
            if (!request.getProviderIdempotencyKey().startsWith("default_") && !request.getProviderIdempotencyKey().startsWith("idem_no_op_")) {
                if (!LlmProviderCapabilityRegistry.hasCapability(providerId, LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY)) {
                    throw new LlmUnsupportedCapabilityException("Provider 'anthropic' does not support capability SUPPORTS_IDEMPOTENCY_KEY");
                }
            }
        }

        // 3. Delegate to Transport Layer
        LlmProviderResponse rawResponse = transport.execute(request);

        // 4. Normalize & Validate via Response Validator
        return LlmResponseValidator.validate(request, rawResponse);
    }
}
