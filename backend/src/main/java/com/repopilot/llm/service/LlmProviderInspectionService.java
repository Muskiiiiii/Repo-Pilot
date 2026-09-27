package com.repopilot.llm.service;

import com.repopilot.llm.dto.ProviderCapabilityResponse;
import com.repopilot.llm.dto.ProviderHealthResponse;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface LlmProviderInspectionService {

    List<ProviderCapabilityResponse> getRegisteredProviders(UUID orgId, User principalUser);

    ProviderCapabilityResponse getProviderCapability(UUID orgId, String providerId, User principalUser);

    ProviderHealthResponse getProviderHealth(UUID orgId, String providerId, User principalUser);
}
