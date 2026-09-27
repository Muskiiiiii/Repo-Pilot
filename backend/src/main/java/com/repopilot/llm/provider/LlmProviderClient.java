package com.repopilot.llm.provider;

import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderCapability;

import java.util.Set;

public interface LlmProviderClient {

    String getProviderId();

    Set<LlmProviderCapability> getCapabilities();

    LlmProviderResponse invoke(LlmProviderRequest request) throws LlmProviderException;
}
