package com.repopilot.llm.provider.adapter;

import com.repopilot.llm.provider.LlmProviderClient;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StubMockProviderAdapter implements LlmProviderClient {

    private final String providerId;

    public StubMockProviderAdapter() {
        this.providerId = "stub-mock";
    }

    public StubMockProviderAdapter(String providerId) {
        this.providerId = providerId != null ? providerId.trim().toLowerCase() : "stub-mock";
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
        LlmProviderCapabilityRegistry.validateProviderAndModel(request.getProviderId(), request.getModelId());

        String providerReqId = "stub_req_" + UUID.randomUUID().toString().substring(0, 8);
        int promptTokens = request.getRequestedTokens() > 0 ? request.getRequestedTokens() : 10;
        int completionTokens = 20;
        int totalTokens = promptTokens + completionTokens;

        return new LlmProviderResponse(
                request.getProviderId(),
                request.getModelId(),
                providerReqId,
                promptTokens,
                completionTokens,
                totalTokens,
                1000L, // 1000 micro USD
                "Stub completion response for: " + request.getInputHash(),
                "resp_hash_" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now(),
                Map.of("adapter", "StubMockProviderAdapter")
        );
    }
}
