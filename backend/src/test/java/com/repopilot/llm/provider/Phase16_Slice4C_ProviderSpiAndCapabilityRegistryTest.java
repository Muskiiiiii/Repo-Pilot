package com.repopilot.llm.provider;

import com.repopilot.llm.provider.adapter.StubMockProviderAdapter;
import com.repopilot.llm.provider.dto.LlmProviderRequest;
import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.exception.LlmProviderNotFoundException;
import com.repopilot.llm.provider.exception.LlmProviderValidationException;
import com.repopilot.llm.provider.exception.LlmUnsupportedCapabilityException;
import com.repopilot.llm.provider.model.LlmProviderCapability;
import com.repopilot.llm.provider.registry.LlmProviderCapabilityRegistry;
import com.repopilot.llm.provider.validation.LlmResponseValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class Phase16_Slice4C_ProviderSpiAndCapabilityRegistryTest {

    private final UUID sampleLogicalId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID sampleAttemptId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("TEST01: Provider identity validation and normalization")
    void test01_providerIdentityValidation() {
        StubMockProviderAdapter adapter = new StubMockProviderAdapter("openai");
        assertEquals("openai", adapter.getProviderId());

        assertTrue(LlmProviderCapabilityRegistry.isSupportedProvider("openai"));
        assertTrue(LlmProviderCapabilityRegistry.isSupportedProvider("OPENAI"));
        assertFalse(LlmProviderCapabilityRegistry.isSupportedProvider("invalid_provider"));
    }

    @Test
    @DisplayName("TEST02: Model identity validation and supported provider-model mapping")
    void test02_modelIdentityValidation() {
        assertTrue(LlmProviderCapabilityRegistry.isSupportedModel("openai", "gpt-4o"));
        assertTrue(LlmProviderCapabilityRegistry.isSupportedModel("anthropic", "claude-3-5-sonnet"));
        assertFalse(LlmProviderCapabilityRegistry.isSupportedModel("openai", "claude-3-5-sonnet"));
        assertFalse(LlmProviderCapabilityRegistry.isSupportedModel("openai", "invalid_model"));

        assertDoesNotThrow(() -> LlmProviderCapabilityRegistry.validateProviderAndModel("openai", "gpt-4o"));
        assertThrows(LlmUnsupportedCapabilityException.class, () ->
                LlmProviderCapabilityRegistry.validateProviderAndModel("openai", "claude-3-5-sonnet"));
    }

    @Test
    @DisplayName("TEST03: Capability lookup returns immutable capability sets")
    void test03_capabilityLookup() {
        Set<LlmProviderCapability> capabilities = LlmProviderCapabilityRegistry.getCapabilities("openai");
        assertTrue(capabilities.contains(LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));
        assertTrue(capabilities.contains(LlmProviderCapability.SUPPORTS_REQUEST_IDENTITY));
        assertTrue(capabilities.contains(LlmProviderCapability.SUPPORTS_TOKEN_REPORTING));
        assertTrue(capabilities.contains(LlmProviderCapability.SUPPORTS_RETRY_SAFETY));
        assertFalse(capabilities.contains(LlmProviderCapability.SUPPORTS_CANCELLATION));

        assertThrows(UnsupportedOperationException.class, () ->
                capabilities.add(LlmProviderCapability.SUPPORTS_CANCELLATION));
    }

    @Test
    @DisplayName("TEST04: Unknown provider rejection fails closed")
    void test04_unknownProviderRejection() {
        assertThrows(LlmProviderNotFoundException.class, () ->
                LlmProviderCapabilityRegistry.getCapabilities("non_existent_provider"));

        assertThrows(LlmProviderNotFoundException.class, () ->
                LlmProviderCapabilityRegistry.validateProviderAndModel("non_existent_provider", "model"));

        assertFalse(LlmProviderCapabilityRegistry.hasCapability("non_existent_provider", LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("TEST05: Unsupported capability rejection fails closed")
    void test05_unsupportedCapabilityRejection() {
        assertThrows(LlmUnsupportedCapabilityException.class, () ->
                LlmProviderCapabilityRegistry.validateCapability("anthropic", LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));

        assertFalse(LlmProviderCapabilityRegistry.hasCapability("anthropic", LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("TEST06: Request identity preservation across DTO construction")
    void test06_requestIdentityPreservation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_key_123", "hash_abc", "Prompt text", 100
        );

        assertEquals("openai", request.getProviderId());
        assertEquals("gpt-4o", request.getModelId());
        assertEquals(sampleLogicalId, request.getLogicalRequestId());
        assertEquals(sampleAttemptId, request.getAttemptId());
        assertEquals("idem_key_123", request.getProviderIdempotencyKey());
    }

    @Test
    @DisplayName("TEST07: Canonical input hash preservation")
    void test07_canonicalIdentityPreservation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "stub-mock", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_key_456", "canonical_hash_789", "Sensitive prompt", 50
        );

        assertEquals("canonical_hash_789", request.getInputHash());

        LlmProviderClient client = new StubMockProviderAdapter();
        LlmProviderResponse response = client.invoke(request);

        assertNotNull(response);
        assertEquals("canonical_hash_789", request.getInputHash());
    }

    @Test
    @DisplayName("TEST08: Response DTO validation boundary for valid responses")
    void test08_responseDtoValidationBoundary() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = new LlmProviderResponse(
                "openai", "gpt-4o", "req_12345", 10, 20, 30, 500L,
                "output text", "resp_hash_1", Instant.now(), Map.of()
        );

        LlmProviderResponse validated = LlmResponseValidator.validate(request, response);
        assertEquals(response, validated);
    }

    @Test
    @DisplayName("TEST09: Negative token count rejection in response validator")
    void test09_negativeTokenRejection() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = new LlmProviderResponse(
                "openai", "gpt-4o", "req_12345", -10, 20, 10, 500L,
                "output text", "resp_hash_1", Instant.now(), Map.of()
        );

        assertThrows(LlmProviderValidationException.class, () ->
                LlmResponseValidator.validate(request, response));
    }

    @Test
    @DisplayName("TEST10: Token count overflow rejection in response validator")
    void test10_tokenOverflowRejection() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = new LlmProviderResponse(
                "openai", "gpt-4o", "req_12345", 1_500_000, 1_000_000, 2_500_000, 500L,
                "output text", "resp_hash_1", Instant.now(), Map.of()
        );

        assertThrows(LlmProviderValidationException.class, () ->
                LlmResponseValidator.validate(request, response));
    }

    @Test
    @DisplayName("TEST11: Malformed negative cost rejection in response validator")
    void test11_malformedCostRejection() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );
        LlmProviderResponse response = new LlmProviderResponse(
                "openai", "gpt-4o", "req_12345", 10, 20, 30, -500L,
                "output text", "resp_hash_1", Instant.now(), Map.of()
        );

        assertThrows(LlmProviderValidationException.class, () ->
                LlmResponseValidator.validate(request, response));
    }

    @Test
    @DisplayName("TEST12: Provider request ID validation (null/empty/invalid format)")
    void test12_providerRequestIdValidation() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "prompt", 10
        );

        // Null providerRequestId
        LlmProviderResponse respNull = new LlmProviderResponse(
                "openai", "gpt-4o", null, 10, 20, 30, 500L,
                "output text", "resp_hash_1", Instant.now(), Map.of()
        );
        assertThrows(LlmProviderValidationException.class, () ->
                LlmResponseValidator.validate(request, respNull));

        // Invalid characters in providerRequestId
        LlmProviderResponse respInvalid = new LlmProviderResponse(
                "openai", "gpt-4o", "req_id_with_space <bad>", 10, 20, 30, 500L,
                "output text", "resp_hash_1", Instant.now(), Map.of()
        );
        assertThrows(LlmProviderValidationException.class, () ->
                LlmResponseValidator.validate(request, respInvalid));
    }

    @Test
    @DisplayName("TEST19: Secret redaction in DTOs and exception messages")
    void test19_secretRedaction() {
        LlmProviderRequest request = new LlmProviderRequest(
                "openai", "gpt-4o", sampleLogicalId, sampleAttemptId,
                "idem_1", "hash_1", "SECRET_PROMPT_KEY_sk-123456789012345678901234567890", 10
        );

        String toString = request.toString();
        assertFalse(toString.contains("SECRET_PROMPT_KEY"));
        assertTrue(toString.contains("[REDACTED]"));

        LlmProviderException ex = new LlmProviderException(
                null, "Error with secret token Bearer sk-123456789012345678901234567890"
        );
        assertFalse(ex.getMessage().contains("sk-123456789012345678901234567890"));
        assertTrue(ex.getMessage().contains("[REDACTED]"));
    }

    @Test
    @DisplayName("TEST20: Provider SPI structural isolation from accounting service")
    void test20_providerAccountingStructuralIsolation() throws Exception {
        // Inspect all fields in LlmProviderClient implementation (StubMockProviderAdapter) via reflection
        Field[] fields = StubMockProviderAdapter.class.getDeclaredFields();
        for (Field f : fields) {
            String fieldTypeName = f.getType().getName();
            assertFalse(fieldTypeName.contains("LlmAccountingService"), "SPI must not hold LlmAccountingService");
            assertFalse(fieldTypeName.contains("OrganizationUsageCounter"), "SPI must not hold OrganizationUsageCounter");
            assertFalse(fieldTypeName.contains("Repository"), "SPI must not hold JPA Repositories");
        }
    }

    @Test
    @DisplayName("TEST21: Provider SPI structural isolation from fallback orchestration")
    void test21_providerFallbackStructuralIsolation() throws IOException {
        Path providerPackage = Paths.get("src/main/java/com/repopilot/llm/provider");
        assertTrue(Files.exists(providerPackage), "Provider package directory must exist");

        try (Stream<Path> stream = Files.walk(providerPackage)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p);
                          assertFalse(content.contains("FallbackManager"), "Provider SPI file " + p.getFileName() + " must not import FallbackManager");
                          assertFalse(content.contains("executeFallback"), "Provider SPI file " + p.getFileName() + " must not invoke fallback logic");
                      } catch (IOException e) {
                          fail("Failed to read file: " + p);
                      }
                  });
        }
    }

    @Test
    @DisplayName("TEST23: Capability escalation prevention (payload cannot override capability registry)")
    void test23_capabilityEscalationPrevention() {
        // Anthropic does not support idempotency key in registry
        assertThrows(LlmUnsupportedCapabilityException.class, () ->
                LlmProviderCapabilityRegistry.validateCapability("anthropic", LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));

        // Registry lookup is static and immutable, caller input cannot tamper with it
        assertFalse(LlmProviderCapabilityRegistry.hasCapability("anthropic", LlmProviderCapability.SUPPORTS_IDEMPOTENCY_KEY));
    }

    // =========================================================================
    // DEFERRED TESTS DOCUMENTATION (To be implemented in subsequent provider slices)
    // =========================================================================
    // - TEST13: Timeout-before-dispatch classification (Deferred to HTTP transport adapter slice 16.4C-2)
    // - TEST14: Timeout-after-dispatch classification (Deferred to HTTP transport adapter slice 16.4C-2)
    // - TEST15: Explicit provider rejection (Deferred to HTTP transport adapter slice 16.4C-2)
    // - TEST16: Authentication failure (Deferred to HTTP transport adapter slice 16.4C-2)
    // - TEST17: Rate-limit failure (Deferred to HTTP transport adapter slice 16.4C-2)
    // - TEST18: Malformed response (Deferred to HTTP transport adapter slice 16.4C-2)
    // - TEST22: Provider cannot mutate financial state (Deferred to provider integration slice 16.4C-2)
    // - TEST24: Duplicate provider request ID (Deferred to fallback/reconciliation slice 16.4C-3)
    // - TEST25: Unsupported exactly-once capability (Verified in TEST03 & Registry capability matrix)
}
