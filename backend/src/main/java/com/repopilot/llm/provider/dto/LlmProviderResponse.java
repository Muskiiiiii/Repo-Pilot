package com.repopilot.llm.provider.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public final class LlmProviderResponse {

    private final String providerId;
    private final String modelId;
    private final String providerRequestId;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final Long providerCalculatedCostMicroUsd;
    private final String responseContent;
    private final String responseHash;
    private final Instant timestamp;
    private final Map<String, String> metadata;

    public LlmProviderResponse(String providerId,
                               String modelId,
                               String providerRequestId,
                               int promptTokens,
                               int completionTokens,
                               int totalTokens,
                               Long providerCalculatedCostMicroUsd,
                               String responseContent,
                               String responseHash,
                               Instant timestamp,
                               Map<String, String> metadata) {
        this.providerId = providerId != null ? providerId.trim().toLowerCase() : "";
        this.modelId = modelId != null ? modelId.trim().toLowerCase() : "";
        this.providerRequestId = providerRequestId;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.providerCalculatedCostMicroUsd = providerCalculatedCostMicroUsd;
        this.responseContent = responseContent != null ? responseContent : "";
        this.responseHash = responseHash != null ? responseHash : "";
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public String getProviderRequestId() {
        return providerRequestId;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public Long getProviderCalculatedCostMicroUsd() {
        return providerCalculatedCostMicroUsd;
    }

    public String getResponseContent() {
        return responseContent;
    }

    public String getResponseHash() {
        return responseHash;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LlmProviderResponse that = (LlmProviderResponse) o;
        return promptTokens == that.promptTokens &&
                completionTokens == that.completionTokens &&
                totalTokens == that.totalTokens &&
                Objects.equals(providerId, that.providerId) &&
                Objects.equals(modelId, that.modelId) &&
                Objects.equals(providerRequestId, that.providerRequestId) &&
                Objects.equals(providerCalculatedCostMicroUsd, that.providerCalculatedCostMicroUsd) &&
                Objects.equals(responseHash, that.responseHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, modelId, providerRequestId, promptTokens, completionTokens, totalTokens, providerCalculatedCostMicroUsd, responseHash);
    }

    @Override
    public String toString() {
        return "LlmProviderResponse{" +
                "providerId='" + providerId + '\'' +
                ", modelId='" + modelId + '\'' +
                ", providerRequestId='" + providerRequestId + '\'' +
                ", promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", totalTokens=" + totalTokens +
                ", providerCalculatedCostMicroUsd=" + providerCalculatedCostMicroUsd +
                ", responseHash='" + responseHash + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
