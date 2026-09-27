package com.repopilot.llm.provider.transport;

import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;

public interface GoogleVertexTransport {
    LlmProviderResponse execute(LlmProviderRequest request) throws LlmProviderException;
}
