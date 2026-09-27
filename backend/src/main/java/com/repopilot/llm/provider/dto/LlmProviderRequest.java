package com.repopilot.llm.provider.dto;

import java.util.Objects;
import java.util.UUID;

public final class LlmProviderRequest {

    private final String providerId;
    private final String modelId;
    private final UUID logicalRequestId;
    private final UUID attemptId;
    private final String providerIdempotencyKey;
    private final String inputHash;
    private final String promptContent;
    private final int requestedTokens;

    public LlmProviderRequest(String providerId,
                              String modelId,
                              UUID logicalRequestId,
                              UUID attemptId,
                              String providerIdempotencyKey,
                              String inputHash,
                              String promptContent,
                              int requestedTokens) {
        this.providerId = validateId("providerId", providerId);
        this.modelId = validateId("modelId", modelId);
        this.logicalRequestId = Objects.requireNonNull(logicalRequestId, "logicalRequestId must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.providerIdempotencyKey = Objects.requireNonNull(providerIdempotencyKey, "providerIdempotencyKey must not be null");
        this.inputHash = Objects.requireNonNull(inputHash, "inputHash must not be null");
        this.promptContent = promptContent != null ? promptContent : "";
        if (requestedTokens < 0) {
            throw new IllegalArgumentException("requestedTokens cannot be negative");
        }
        this.requestedTokens = requestedTokens;
    }

    private static String validateId(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be null or empty");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException(name + " exceeds maximum length of 64 characters");
        }
        return value.trim().toLowerCase();
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModelId() {
        return modelId;
    }

    public UUID getLogicalRequestId() {
        return logicalRequestId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public String getProviderIdempotencyKey() {
        return providerIdempotencyKey;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getPromptContent() {
        return promptContent;
    }

    public int getRequestedTokens() {
        return requestedTokens;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LlmProviderRequest that = (LlmProviderRequest) o;
        return requestedTokens == that.requestedTokens &&
                Objects.equals(providerId, that.providerId) &&
                Objects.equals(modelId, that.modelId) &&
                Objects.equals(logicalRequestId, that.logicalRequestId) &&
                Objects.equals(attemptId, that.attemptId) &&
                Objects.equals(providerIdempotencyKey, that.providerIdempotencyKey) &&
                Objects.equals(inputHash, that.inputHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, modelId, logicalRequestId, attemptId, providerIdempotencyKey, inputHash, requestedTokens);
    }

    @Override
    public String toString() {
        return "LlmProviderRequest{" +
                "providerId='" + providerId + '\'' +
                ", modelId='" + modelId + '\'' +
                ", logicalRequestId=" + logicalRequestId +
                ", attemptId=" + attemptId +
                ", providerIdempotencyKey='" + providerIdempotencyKey + '\'' +
                ", inputHash='" + inputHash + '\'' +
                ", promptContent='[REDACTED]'" +
                ", requestedTokens=" + requestedTokens +
                '}';
    }
}
