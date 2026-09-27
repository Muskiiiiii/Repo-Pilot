package com.repopilot.llm.provider.registry;

import com.repopilot.llm.provider.exception.LlmProviderNotFoundException;
import com.repopilot.llm.provider.exception.LlmUnsupportedCapabilityException;
import com.repopilot.llm.provider.model.LlmProviderCapability;

import java.util.*;

public final class LlmProviderCapabilityRegistry {

    private static final Map<String, Set<LlmProviderCapability>> PROVIDER_CAPABILITIES;
    private static final Map<String, Set<String>> PROVIDER_MODELS;

    static {
        Map<String, Set<LlmProviderCapability>> capMap = new HashMap<>();
        // OpenAI
        capMap.put("openai", Set.of(
                LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY,
                LlmProviderCapability.SUPPORTS_REQUEST_IDENTITY,
                LlmProviderCapability.SUPPORTS_TOKEN_REPORTING,
                LlmProviderCapability.SUPPORTS_RETRY_SAFETY,
                LlmProviderCapability.SUPPORTS_EXECUTION_RECONCILIATION,
                LlmProviderCapability.RETRY_AUTHORITATIVELY_RECONCILES
        ));
        // Anthropic
        capMap.put("anthropic", Set.of(
                LlmProviderCapability.SUPPORTS_REQUEST_IDENTITY,
                LlmProviderCapability.SUPPORTS_TOKEN_REPORTING
        ));
        // Google Vertex
        capMap.put("google-vertex", Set.of(
                LlmProviderCapability.SUPPORTS_REQUEST_IDENTITY,
                LlmProviderCapability.SUPPORTS_TOKEN_REPORTING
        ));
        // Stub Mock (For testing)
        capMap.put("stub-mock", Set.of(
                LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY,
                LlmProviderCapability.SUPPORTS_REQUEST_IDENTITY,
                LlmProviderCapability.SUPPORTS_TOKEN_REPORTING,
                LlmProviderCapability.SUPPORTS_COST_REPORTING,
                LlmProviderCapability.SUPPORTS_RETRY_SAFETY,
                LlmProviderCapability.SUPPORTS_CANCELLATION,
                LlmProviderCapability.SUPPORTS_EXECUTION_RECONCILIATION,
                LlmProviderCapability.RETRY_AUTHORITATIVELY_RECONCILES
        ));
        PROVIDER_CAPABILITIES = Collections.unmodifiableMap(capMap);

        Map<String, Set<String>> modelMap = new HashMap<>();
        modelMap.put("openai", Set.of("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o1-preview"));
        modelMap.put("anthropic", Set.of("claude-3-5-sonnet", "claude-3-haiku", "claude-3-opus"));
        modelMap.put("google-vertex", Set.of("gemini-1.5-pro", "gemini-1.5-flash"));
        modelMap.put("stub-mock", Set.of("gpt-4o", "claude-3-5-sonnet", "stub-model"));
        PROVIDER_MODELS = Collections.unmodifiableMap(modelMap);
    }

    public static List<String> getRegisteredProviderIds() {
        List<String> providerIds = new ArrayList<>(PROVIDER_CAPABILITIES.keySet());
        Collections.sort(providerIds);
        return Collections.unmodifiableList(providerIds);
    }

    public static Set<String> getSupportedModels(String providerId) {
        if (providerId == null) {
            return Collections.emptySet();
        }
        String normalized = normalize(providerId);
        Set<String> models = PROVIDER_MODELS.get(normalized);
        if (models == null) {
            throw new LlmProviderNotFoundException(providerId);
        }
        return models;
    }

    private LlmProviderCapabilityRegistry() {
    }

    public static Set<LlmProviderCapability> getCapabilities(String providerId) {
        String normalizedProvider = normalize(providerId);
        Set<LlmProviderCapability> capabilities = PROVIDER_CAPABILITIES.get(normalizedProvider);
        if (capabilities == null) {
            throw new LlmProviderNotFoundException(providerId);
        }
        return capabilities;
    }

    public static boolean hasCapability(String providerId, LlmProviderCapability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        try {
            Set<LlmProviderCapability> capabilities = getCapabilities(providerId);
            return capabilities.contains(capability);
        } catch (LlmProviderNotFoundException e) {
            return false;
        }
    }

    public static void validateCapability(String providerId, LlmProviderCapability capability) {
        if (!hasCapability(providerId, capability)) {
            throw new LlmUnsupportedCapabilityException("Provider '" + providerId + "' does not support capability: " + capability);
        }
    }

    public static boolean isSupportedProvider(String providerId) {
        return providerId != null && PROVIDER_CAPABILITIES.containsKey(normalize(providerId));
    }

    public static boolean isSupportedModel(String providerId, String modelId) {
        if (providerId == null || modelId == null) return false;
        Set<String> models = PROVIDER_MODELS.get(normalize(providerId));
        return models != null && models.contains(modelId.trim().toLowerCase());
    }

    public static void validateProviderAndModel(String providerId, String modelId) {
        if (!isSupportedProvider(providerId)) {
            throw new LlmProviderNotFoundException(providerId);
        }
        if (!isSupportedModel(providerId, modelId)) {
            throw new LlmUnsupportedCapabilityException("Model '" + modelId + "' is not supported by provider '" + providerId + "'");
        }
    }

    private static String normalize(String providerId) {
        return providerId != null ? providerId.trim().toLowerCase() : "";
    }
}
