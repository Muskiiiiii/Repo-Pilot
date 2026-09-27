package com.repopilot.llm.dto;

import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;

import java.util.Collections;
import java.util.Set;

public class ProviderCapabilityResponse {

    private String providerId;
    private Set<String> supportedModels;
    private boolean supportsIdempotencyKey;
    private boolean supportsProviderRequestIdentity;
    private boolean supportsTokenReporting;
    private boolean supportsCostReporting;
    private boolean supportsRetrySafety;
    private boolean supportsCancellation;
    private boolean supportsExecutionReconciliation;
    private boolean retryAuthoritativelyReconciles;

    public ProviderCapabilityResponse() {
    }

    public static ProviderCapabilityResponse fromRegistry(String providerId) {
        Set<LlmProviderCapability> capabilities = LlmProviderCapabilityRegistry.getCapabilities(providerId);
        Set<String> models = LlmProviderCapabilityRegistry.getSupportedModels(providerId);

        ProviderCapabilityResponse response = new ProviderCapabilityResponse();
        response.setProviderId(providerId);
        response.setSupportedModels(models != null ? Collections.unmodifiableSet(models) : Collections.emptySet());
        response.setSupportsIdempotencyKey(capabilities.contains(LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));
        response.setSupportsProviderRequestIdentity(capabilities.contains(LlmProviderCapability.SUPPORTS_REQUEST_IDENTITY));
        response.setSupportsTokenReporting(capabilities.contains(LlmProviderCapability.SUPPORTS_TOKEN_REPORTING));
        response.setSupportsCostReporting(capabilities.contains(LlmProviderCapability.SUPPORTS_COST_REPORTING));
        response.setSupportsRetrySafety(capabilities.contains(LlmProviderCapability.SUPPORTS_RETRY_SAFETY));
        response.setSupportsCancellation(capabilities.contains(LlmProviderCapability.SUPPORTS_CANCELLATION));
        response.setSupportsExecutionReconciliation(capabilities.contains(LlmProviderCapability.SUPPORTS_EXECUTION_RECONCILIATION));
        response.setRetryAuthoritativelyReconciles(capabilities.contains(LlmProviderCapability.RETRY_AUTHORITATIVELY_RECONCILES));
        return response;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public Set<String> getSupportedModels() {
        return supportedModels;
    }

    public void setSupportedModels(Set<String> supportedModels) {
        this.supportedModels = supportedModels;
    }

    public boolean isSupportsIdempotencyKey() {
        return supportsIdempotencyKey;
    }

    public void setSupportsIdempotencyKey(boolean supportsIdempotencyKey) {
        this.supportsIdempotencyKey = supportsIdempotencyKey;
    }

    public boolean isSupportsProviderRequestIdentity() {
        return supportsProviderRequestIdentity;
    }

    public void setSupportsProviderRequestIdentity(boolean supportsProviderRequestIdentity) {
        this.supportsProviderRequestIdentity = supportsProviderRequestIdentity;
    }

    public boolean isSupportsTokenReporting() {
        return supportsTokenReporting;
    }

    public void setSupportsTokenReporting(boolean supportsTokenReporting) {
        this.supportsTokenReporting = supportsTokenReporting;
    }

    public boolean isSupportsCostReporting() {
        return supportsCostReporting;
    }

    public void setSupportsCostReporting(boolean supportsCostReporting) {
        this.supportsCostReporting = supportsCostReporting;
    }

    public boolean isSupportsRetrySafety() {
        return supportsRetrySafety;
    }

    public void setSupportsRetrySafety(boolean supportsRetrySafety) {
        this.supportsRetrySafety = supportsRetrySafety;
    }

    public boolean isSupportsCancellation() {
        return supportsCancellation;
    }

    public void setSupportsCancellation(boolean supportsCancellation) {
        this.supportsCancellation = supportsCancellation;
    }

    public boolean isSupportsExecutionReconciliation() {
        return supportsExecutionReconciliation;
    }

    public void setSupportsExecutionReconciliation(boolean supportsExecutionReconciliation) {
        this.supportsExecutionReconciliation = supportsExecutionReconciliation;
    }

    public boolean isRetryAuthoritativelyReconciles() {
        return retryAuthoritativelyReconciles;
    }

    public void setRetryAuthoritativelyReconciles(boolean retryAuthoritativelyReconciles) {
        this.retryAuthoritativelyReconciles = retryAuthoritativelyReconciles;
    }
}
