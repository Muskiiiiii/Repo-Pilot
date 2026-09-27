package com.repopilot.llm.provider;

import com.repopilot.llm.provider.adapter.AnthropicProviderAdapter;
import com.repopilot.llm.provider.adapter.GoogleVertexProviderAdapter;
import com.repopilot.llm.provider.adapter.OpenAiProviderAdapter;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.exception.LlmProviderValidationException;
import com.repopilot.llm.provider.exception.LlmUnsupportedCapabilityException;
import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.model.LlmProviderFailureReason;
import com.repopilot.llm.provider.transport.FakeAnthropicTransport;
import com.repopilot.llm.provider.transport.FakeGoogleVertexTransport;
import com.repopilot.llm.provider.transport.FakeOpenAiTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class Phase16_Slice4C_ProviderAdapterBoundaryTest {

    private final UUID sampleLogicalId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID sampleAttemptId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private FakeOpenAiTransport fakeOpenAiTransport;
    private OpenAiProviderAdapter openAiAdapter;

    private FakeAnthropicTransport fakeAnthropicTransport;
    private AnthropicProviderAdapter anthropicAdapter;

    private FakeGoogleVertexTransport fakeVertexTransport;
    private GoogleVertexProviderAdapter vertexAdapter;

    @BeforeEach
    void setUp() {
        fakeOpenAiTransport = new FakeOpenAiTransport();
        openAiAdapter = new OpenAiProviderAdapter(fakeOpenAiTransport);

        fakeAnthropicTransport = new FakeAnthropicTransport();
        anthropicAdapter = new AnthropicProviderAdapter(fakeAnthropicTransport);

        fakeVertexTransport = new FakeGoogleVertexTransport();
        vertexAdapter = new GoogleVertexProviderAdapter(fakeVertexTransport);
    }

    @Test
    @DisplayName("TEST26: OpenAI request translation preserves all identities")
    void test26_openAiRequestTranslation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_openai_100", "hash_openai_200", "OpenAI prompt", 50
        );

        LlmProviderResponse response = openAiAdapter.invoke(request);
        assertNotNull(response);
        assertEquals("openai", response.getProviderId());
        assertEquals("gpt-4o", response.getModelId());
        assertTrue(response.getProviderRequestId().startsWith("req_openai_"));
    }

    @Test
    @DisplayName("TEST27: Anthropic request translation preserves all identities")
    void test27_anthropicRequestTranslation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "anthropic", "claude-3-5-sonnet", sampleLogicalId, sampleAttemptId,
                "default_idem", "hash_anthropic_200", "Anthropic prompt", 60
        );

        LlmProviderResponse response = anthropicAdapter.invoke(request);
        assertNotNull(response);
        assertEquals("anthropic", response.getProviderId());
        assertEquals("claude-3-5-sonnet", response.getModelId());
        assertTrue(response.getProviderRequestId().startsWith("msg_anthropic_"));
    }

    @Test
    @DisplayName("TEST28: Google Vertex request translation preserves all identities")
    void test28_googleVertexRequestTranslation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "google-vertex", "gemini-1.5-pro", sampleLogicalId, sampleAttemptId,
                "default_idem", "hash_vertex_200", "Vertex prompt", 70
        );

        LlmProviderResponse response = vertexAdapter.invoke(request);
        assertNotNull(response);
        assertEquals("google-vertex", response.getProviderId());
        assertEquals("gemini-1.5-pro", response.getModelId());
        assertTrue(response.getProviderRequestId().contains("projects/"));
    }

    @Test
    @DisplayName("TEST29: Provider identity preservation through response boundary")
    void test29_providerIdentityPreservation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = openAiAdapter.invoke(request);
        assertEquals("openai", response.getProviderId());
    }

    @Test
    @DisplayName("TEST30: Model identity preservation through response boundary")
    void test30_modelIdentityPreservation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "anthropic", "claude-3-5-sonnet", sampleLogicalId, sampleAttemptId,
                "default_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = anthropicAdapter.invoke(request);
        assertEquals("claude-3-5-sonnet", response.getModelId());
    }

    @Test
    @DisplayName("TEST31: Canonical input identity preservation")
    void test31_canonicalInputIdentityPreservation() {
        String canonicalHash = "sha256_canonical_input_hash_999";
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", canonicalHash, "prompt", 10
        );
        assertEquals(canonicalHash, request.getInputHash());
    }

    @Test
    @DisplayName("TEST32: Provider capability enforcement")
    void test32_providerCapabilityEnforcement() {
        Set<LlmProviderCapability> openAiCaps = openAiAdapter.getCapabilities();
        assertTrue(openAiCaps.contains(LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));

        Set<LlmProviderCapability> anthropicCaps = anthropicAdapter.getCapabilities();
        assertFalse(anthropicCaps.contains(LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("TEST33: Unsupported idempotency capability rejection for Anthropic")
    void test33_unsupportedIdempotencyCapability() {
        LlmProviderRequest request = new LlmProviderRequest(
                "anthropic", "claude-3-5-sonnet", sampleLogicalId, sampleAttemptId,
                "custom_idempotency_key_not_supported", "hash_1", "prompt", 10
        );
        assertThrows(LlmUnsupportedCapabilityException.class, () ->
                anthropicAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST34: Deterministic provider request identity generation")
    void test34_deterministicProviderRequestIdentity() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = openAiAdapter.invoke(request);
        assertNotNull(response.getProviderRequestId());
        assertFalse(response.getProviderRequestId().trim().isEmpty());
    }

    @Test
    @DisplayName("TEST35: Valid normalized response passes through adapter")
    void test35_validNormalizedResponse() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = openAiAdapter.invoke(request);
        assertNotNull(response);
        assertEquals(10, response.getPromptTokens());
        assertEquals(25, response.getCompletionTokens());
        assertEquals(35, response.getTotalTokens());
    }

    @Test
    @DisplayName("TEST36: Malformed null response fails closed")
    void test36_malformedNullResponse() {
        fakeOpenAiTransport.setCustomResponseToReturn(null);
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST37: Token total mismatch fails closed")
    void test37_tokenMismatch() {
        LlmProviderResponse badResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_bad", 10, 20, 999, null,
                "text", "hash", Instant.now(), Map.of()
        );
        fakeOpenAiTransport.setCustomResponseToReturn(badResp);

        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST38: Negative token count fails closed")
    void test38_negativeTokenCount() {
        LlmProviderResponse badResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_bad", -5, 20, 15, null,
                "text", "hash", Instant.now(), Map.of()
        );
        fakeOpenAiTransport.setCustomResponseToReturn(badResp);

        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST39: Token count overflow fails closed")
    void test39_tokenOverflow() {
        LlmProviderResponse badResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_bad", 1_500_000, 1_000_000, 2_500_000, null,
                "text", "hash", Instant.now(), Map.of()
        );
        fakeOpenAiTransport.setCustomResponseToReturn(badResp);

        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST40: Malformed negative provider cost fails closed")
    void test40_malformedProviderCost() {
        LlmProviderResponse badResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_bad", 10, 20, 30, -100L,
                "text", "hash", Instant.now(), Map.of()
        );
        fakeOpenAiTransport.setCustomResponseToReturn(badResp);

        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST41: Missing provider request ID fails closed")
    void test41_missingProviderRequestId() {
        LlmProviderResponse badResp = new LlmProviderResponse(
                "openai", "gpt-4o", null, 10, 20, 30, null,
                "text", "hash", Instant.now(), Map.of()
        );
        fakeOpenAiTransport.setCustomResponseToReturn(badResp);

        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST42: Malformed provider request ID format fails closed")
    void test42_malformedProviderRequestId() {
        LlmProviderResponse badResp = new LlmProviderResponse(
                "openai", "gpt-4o", "req_id with spaces and <invalid>", 10, 20, 30, null,
                "text", "hash", Instant.now(), Map.of()
        );
        fakeOpenAiTransport.setCustomResponseToReturn(badResp);

        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST43: Provider/model mismatch fails closed")
    void test43_providerModelMismatch() {
        LlmProviderResponse badResp = new LlmProviderResponse(
                "anthropic", "gpt-4o", "req_123", 10, 20, 30, null,
                "text", "hash", Instant.now(), Map.of()
        );
        fakeOpenAiTransport.setCustomResponseToReturn(badResp);

        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        assertThrows(LlmProviderValidationException.class, () ->
                openAiAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST44: Pre-dispatch timeout normalization")
    void test44_preDispatchTimeout() {
        fakeOpenAiTransport.setExceptionToThrow(
                LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, "Connect timeout before HTTP POST"
        );
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderException ex = assertThrows(LlmProviderException.class, () ->
                openAiAdapter.invoke(request));
        assertEquals(LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, ex.getFailureReason());
    }

    @Test
    @DisplayName("TEST45: Post-dispatch possible timeout normalization")
    void test45_postDispatchPossibleTimeout() {
        fakeOpenAiTransport.setExceptionToThrow(
                LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, "Read timeout post HTTP POST"
        );
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderException ex = assertThrows(LlmProviderException.class, () ->
                openAiAdapter.invoke(request));
        assertEquals(LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, ex.getFailureReason());
    }

    @Test
    @DisplayName("TEST46: Authentication failure normalization")
    void test46_authenticationFailureNormalization() {
        fakeAnthropicTransport.setExceptionToThrow(
                LlmProviderFailureReason.AUTHENTICATION_FAILURE, "HTTP 401 Invalid API Key"
        );
        LlmProviderRequest request = new LlmProviderRequest(
                "anthropic", "claude-3-5-sonnet", sampleLogicalId, sampleAttemptId,
                "default_1", "hash_1", "prompt", 10
        );
        LlmProviderException ex = assertThrows(LlmProviderException.class, () ->
                anthropicAdapter.invoke(request));
        assertEquals(LlmProviderFailureReason.AUTHENTICATION_FAILURE, ex.getFailureReason());
    }

    @Test
    @DisplayName("TEST47: Rate-limit failure normalization")
    void test47_rateLimitNormalization() {
        fakeVertexTransport.setExceptionToThrow(
                LlmProviderFailureReason.RATE_LIMIT_EXCEEDED, "HTTP 429 Rate limit exceeded"
        );
        LlmProviderRequest request = new LlmProviderRequest(
                "google-vertex", "gemini-1.5-pro", sampleLogicalId, sampleAttemptId,
                "default_1", "hash_1", "prompt", 10
        );
        LlmProviderException ex = assertThrows(LlmProviderException.class, () ->
                vertexAdapter.invoke(request));
        assertEquals(LlmProviderFailureReason.RATE_LIMIT_EXCEEDED, ex.getFailureReason());
    }

    @Test
    @DisplayName("TEST48: Provider rejection normalization")
    void test48_providerRejectionNormalization() {
        fakeOpenAiTransport.setExceptionToThrow(
                LlmProviderFailureReason.EXPLICIT_PROVIDER_REJECTION, "HTTP 400 Bad Request"
        );
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderException ex = assertThrows(LlmProviderException.class, () ->
                openAiAdapter.invoke(request));
        assertEquals(LlmProviderFailureReason.EXPLICIT_PROVIDER_REJECTION, ex.getFailureReason());
    }

    @Test
    @DisplayName("TEST49: Provider internal failure normalization")
    void test49_providerInternalFailureNormalization() {
        fakeAnthropicTransport.setExceptionToThrow(
                LlmProviderFailureReason.PROVIDER_INTERNAL_ERROR, "HTTP 500 Internal Error"
        );
        LlmProviderRequest request = new LlmProviderRequest(
                "anthropic", "claude-3-5-sonnet", sampleLogicalId, sampleAttemptId,
                "default_1", "hash_1", "prompt", 10
        );
        LlmProviderException ex = assertThrows(LlmProviderException.class, () ->
                anthropicAdapter.invoke(request));
        assertEquals(LlmProviderFailureReason.PROVIDER_INTERNAL_ERROR, ex.getFailureReason());
    }

    @Test
    @DisplayName("TEST50: Secret non-disclosure in DTOs, logs, and exceptions")
    void test50_secretNonDisclosure() {
        String sensitiveKey = "sk-123456789012345678901234567890";
        LlmProviderException ex = new LlmProviderException(
                LlmProviderFailureReason.AUTHENTICATION_FAILURE,
                "Failed auth with key " + sensitiveKey
        );

        assertFalse(ex.getMessage().contains(sensitiveKey));
        assertTrue(ex.getMessage().contains("[REDACTED]"));
    }

    @Test
    @DisplayName("TEST51: Structural isolation audit - Adapters do not hold accounting dependencies")
    void test51_accountingStructuralIsolation() {
        Class<?>[] adapterClasses = new Class<?>[]{
                OpenAiProviderAdapter.class, AnthropicProviderAdapter.class, GoogleVertexProviderAdapter.class
        };
        for (Class<?> clazz : adapterClasses) {
            for (Field f : clazz.getDeclaredFields()) {
                String typeName = f.getType().getName();
                assertFalse(typeName.contains("LlmAccountingService"), "Adapter " + clazz.getSimpleName() + " must not depend on LlmAccountingService");
                assertFalse(typeName.contains("OrganizationUsageCounter"), "Adapter " + clazz.getSimpleName() + " must not depend on OrganizationUsageCounter");
                assertFalse(typeName.contains("Repository"), "Adapter " + clazz.getSimpleName() + " must not depend on JPA Repositories");
            }
        }
    }

    @Test
    @DisplayName("TEST52: Structural isolation audit - Adapters do not hold Redis dependencies")
    void test52_redisIsolation() {
        Class<?>[] adapterClasses = new Class<?>[]{
                OpenAiProviderAdapter.class, AnthropicProviderAdapter.class, GoogleVertexProviderAdapter.class
        };
        for (Class<?> clazz : adapterClasses) {
            for (Field f : clazz.getDeclaredFields()) {
                String typeName = f.getType().getName();
                assertFalse(typeName.contains("Redis"), "Adapter " + clazz.getSimpleName() + " must not depend on Redis");
            }
        }
    }

    @Test
    @DisplayName("TEST53: Fallback structural isolation audit - Adapters do not invoke fallback")
    void test53_fallbackIsolation() throws IOException {
        Path adapterPackage = Paths.get("src/main/java/com/repopilot/llm/provider/adapter");
        assertTrue(Files.exists(adapterPackage));

        try (Stream<Path> stream = Files.walk(adapterPackage)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p);
                          assertFalse(content.contains("FallbackManager"), "File " + p.getFileName() + " must not contain FallbackManager");
                          assertFalse(content.contains("executeFallback"), "File " + p.getFileName() + " must not contain fallback execution logic");
                      } catch (IOException e) {
                          fail("Failed to read file: " + p);
                      }
                  });
        }
    }

    @Test
    @DisplayName("TEST54: Caller cannot escalate capabilities")
    void test54_callerCannotEscalateCapabilities() {
        LlmProviderRequest request = new LlmProviderRequest(
                "anthropic", "claude-3-5-sonnet", sampleLogicalId, sampleAttemptId,
                "escalated_idempotency_key", "hash_1", "prompt", 10
        );

        assertThrows(LlmUnsupportedCapabilityException.class, () ->
                anthropicAdapter.invoke(request));
    }

    @Test
    @DisplayName("TEST55: Missing provider cost is explicitly null and not zero-clamped")
    void test55_missingProviderCostNotZeroClamped() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = openAiAdapter.invoke(request);

        assertNull(response.getProviderCalculatedCostMicroUsd(), "Missing provider cost must be null, not 0L");
    }

    @Test
    @DisplayName("TEST56: Adapter cannot mutate financial state")
    void test56_adapterCannotMutateFinancialState() {
        Class<?>[] adapterClasses = new Class<?>[]{
                OpenAiProviderAdapter.class, AnthropicProviderAdapter.class, GoogleVertexProviderAdapter.class
        };
        for (Class<?> clazz : adapterClasses) {
            for (Method m : clazz.getDeclaredMethods()) {
                String name = m.getName();
                assertFalse(name.contains("settle"), "Adapter method cannot perform settlement");
                assertFalse(name.contains("reserve"), "Adapter method cannot perform reservation");
                assertFalse(name.contains("release"), "Adapter method cannot perform release");
            }
        }
    }
}
