package com.repopilot.llm.provider.transport;

import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderFailureReason;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class FakeAnthropicTransport implements AnthropicTransport {

    private LlmProviderFailureReason exceptionToThrow;
    private String customExceptionMessage;
    private boolean overrideResponse = false;
    private LlmProviderResponse customResponseToReturn;

    public void setExceptionToThrow(LlmProviderFailureReason failureReason, String message) {
        this.exceptionToThrow = failureReason;
        this.customExceptionMessage = message;
    }

    public void setCustomResponseToReturn(LlmProviderResponse response) {
        this.overrideResponse = true;
        this.customResponseToReturn = response;
    }

    @Override
    public LlmProviderResponse execute(LlmProviderRequest request) throws LlmProviderException {
        if (exceptionToThrow != null) {
            throw new LlmProviderException(exceptionToThrow, customExceptionMessage != null ? customExceptionMessage : "Simulated transport exception");
        }
        if (overrideResponse) {
            return customResponseToReturn;
        }

        String reqId = "msg_anthropic_" + UUID.randomUUID().toString().substring(0, 8);
        int promptTokens = request.getRequestedTokens() > 0 ? request.getRequestedTokens() : 20;
        int completionTokens = 30;
        int totalTokens = promptTokens + completionTokens;

        return new LlmProviderResponse(
                request.getProviderId(),
                request.getModelId(),
                reqId,
                promptTokens,
                completionTokens,
                totalTokens,
                null,
                "Anthropic response content for prompt hash: " + request.getInputHash(),
                "resp_hash_anthropic_" + UUID.randomUUID().toString().substring(0, 8),
                Instant.now(),
                Map.of("provider", "anthropic", "type", "message")
        );
    }
}
