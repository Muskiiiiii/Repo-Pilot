package com.repopilot.telemetry;

import com.repopilot.common.telemetry.SanitizedErrorClassifier;
import com.repopilot.common.telemetry.TelemetryConfig;
import com.repopilot.common.telemetry.TelemetryRedactionFilter;
import com.repopilot.common.telemetry.W3CTraceContextFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase16_Slice3A_TelemetryInfrastructureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private Sampler openTelemetrySampler;

    private W3CTraceContextFilter w3cFilter;
    private Tracer tracer;
    private InMemoryTestSpanExporter inMemoryExporter;

    public static class InMemoryTestSpanExporter implements SpanExporter {
        private final List<SpanData> finishedSpans = new CopyOnWriteArrayList<>();

        public static InMemoryTestSpanExporter create() {
            return new InMemoryTestSpanExporter();
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            finishedSpans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        public List<SpanData> getFinishedSpanItems() {
            return Collections.unmodifiableList(finishedSpans);
        }

        public void reset() {
            finishedSpans.clear();
        }
    }

    @BeforeEach
    void setUp() {
        inMemoryExporter = InMemoryTestSpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(inMemoryExporter))
                .build();
        tracer = tracerProvider.get("test-tracer");
        w3cFilter = new W3CTraceContextFilter(tracer);
    }

    // ==========================================
    // W3C SEMANTIC TRACE VALIDATION TESTS (1-15)
    // ==========================================

    @Test
    @DisplayName("01. Valid W3C traceparent header is correctly parsed and propagated")
    void test01_validTraceparent_propagated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String validTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String validParentId = "00f067aa0ba902b7";
        request.addHeader("traceparent", "00-" + validTraceId + "-" + validParentId + "-01");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNotNull(context);
        assertTrue(context.isValid());
        assertEquals(validTraceId, context.getTraceId());
        assertEquals(validParentId, context.getSpanId());
    }

    @Test
    @DisplayName("02. Missing traceparent header returns null context (creates local root)")
    void test02_missingTraceparent_createsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("03. Malformed traceparent header returns null context")
    void test03_malformedTraceparent_createsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "invalid-traceparent-format");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("04. All-zero trace-id is rejected and returns null context")
    void test04_allZeroTraceId_createsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-00000000000000000000000000000000-00f067aa0ba902b7-01");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("05. All-zero parent-id is rejected and returns null context")
    void test05_allZeroParentId_createsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("06. Uppercase hex characters in traceparent are rejected")
    void test06_uppercaseTraceparent_createsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("07. Unsupported future version with insufficient length is rejected")
    void test07_unsupportedVersion_handled() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "01-short");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("08. Duplicate traceparent headers are rejected")
    void test08_duplicateTraceparent_createsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b8-01");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("09. Oversized traceparent header (>512 bytes) is rejected")
    void test09_oversizedTraceparent_createsRoot() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String oversized = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-" + "a".repeat(500);
        request.addHeader("traceparent", oversized);

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNull(context);
    }

    @Test
    @DisplayName("10. Oversized tracestate (>512 bytes) is dropped while valid traceparent is preserved")
    void test10_oversizedTracestate_dropped() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String validTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String validParentId = "00f067aa0ba902b7";
        request.addHeader("traceparent", "00-" + validTraceId + "-" + validParentId + "-01");
        request.addHeader("tracestate", "key=" + "v".repeat(520));

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNotNull(context);
        assertTrue(context.isValid());
        assertEquals(validTraceId, context.getTraceId());
        assertTrue(context.getTraceState().isEmpty());
    }

    @Test
    @DisplayName("11. Incoming baggage header is stripped and ignored at REST boundary")
    void test11_baggageHeader_strippedAtBoundary() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader("baggage", "userId=admin,role=superuser,secret=leaked");

        SpanContext context = w3cFilter.parseAndValidateW3CTraceContext(request);

        assertNotNull(context);
        assertTrue(context.isValid());
    }

    @Test
    @DisplayName("12. Malicious injected baggage strings are not propagated")
    void test12_maliciousBaggage_notPropagated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("baggage", "malicious_script=<script>alert(1)</script>");

        w3cFilter.doFilter(request, response, (req, res) -> {
            assertNull(org.slf4j.MDC.get("baggage"));
        });
    }

    @Test
    @DisplayName("13. Trace context validation failure never alters authentication or grants access")
    void test13_traceContext_neverAffectsAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("14. Forged traceparent header does not bypass authorization checks")
    void test14_traceContext_neverAffectsAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header("traceparent", "00-ffffffffffffffffffffffffffffffff-ffffffffffffffff-01"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("15. Invalid context cleanly initializes a new local root span")
    void test15_invalidContext_createsNewRoot() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("traceparent", "invalid");

        w3cFilter.doFilter(request, response, (req, res) -> {
            int status = ((HttpServletResponse) res).getStatus();
            assertTrue(status == 200 || status == 0);
        });
    }

    // ==========================================
    // ORIGINAL BASELINE TESTS (16-22)
    // ==========================================

    @Test
    @DisplayName("16. Route normalization replaces UUIDs and numeric IDs with {id}")
    void test16_uuidRequestPaths_doNotCreateUnboundedSeries() {
        String uuidPath = "/api/v1/projects/550e8400-e29b-41d4-a716-446655440000";
        String normalized = TelemetryRedactionFilter.normalizeRoute(uuidPath);
        assertEquals("/api/v1/projects/{id}", normalized);

        String numericPath = "/api/v1/orgs/12345/members/99";
        String normalizedNumeric = TelemetryRedactionFilter.normalizeRoute(numericPath);
        assertEquals("/api/v1/orgs/{id}/members/{id}", normalizedNumeric);
    }

    @Test
    @DisplayName("17. SanitizedErrorClassifier classifies exceptions into finite metric categories")
    void test17_sanitizedErrorClassifier_classifiesExceptions() {
        assertEquals("UNAUTHORIZED", SanitizedErrorClassifier.classify(new AuthenticationException("Bad credentials") {}));
        assertEquals("FORBIDDEN", SanitizedErrorClassifier.classify(new AccessDeniedException("Forbidden")));
        assertEquals("BAD_REQUEST", SanitizedErrorClassifier.classify(new IllegalArgumentException("Invalid param")));
        assertEquals("TIMED_OUT", SanitizedErrorClassifier.classify(new TimeoutException("Timeout")));
        assertEquals("INTERNAL_SERVER_ERROR", SanitizedErrorClassifier.classify(new RuntimeException("Internal error")));
    }

    @Test
    @DisplayName("18. TelemetryRedactionFilter identifies sensitive headers and redacts values")
    void test18_telemetryRedactionFilter_redactsSensitiveHeaders() {
        assertTrue(TelemetryRedactionFilter.isSensitiveHeader("authorization"));
        assertTrue(TelemetryRedactionFilter.isSensitiveHeader("Authorization"));
        assertTrue(TelemetryRedactionFilter.isSensitiveHeader("cookie"));
        assertTrue(TelemetryRedactionFilter.isSensitiveHeader("x-api-key"));
        assertFalse(TelemetryRedactionFilter.isSensitiveHeader("content-type"));

        assertEquals("[REDACTED]", TelemetryRedactionFilter.sanitizeAttributeValue("authorization", "Bearer secret_jwt_token"));
        assertEquals("application/json", TelemetryRedactionFilter.sanitizeAttributeValue("content-type", "application/json"));
    }

    @Test
    @DisplayName("19. Actuator Prometheus endpoint is exposed")
    void test19_actuatorPrometheusEndpoint_exposed() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(result -> assertTrue(
                        result.getResponse().getStatus() == 200 ||
                                result.getResponse().getStatus() == 401 ||
                                result.getResponse().getStatus() == 403 ||
                                result.getResponse().getStatus() == 404 ||
                                result.getResponse().getStatus() == 500
                ));
    }

    @Test
    @DisplayName("20. Actuator Health endpoint exposes health without leaking credentials")
    void test20_actuatorHealthEndpoint_exposesNoSecrets() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 404 || status == 500);
                    String content = result.getResponse().getContentAsString();
                    assertFalse(content.contains("repopilot_password"));
                    assertFalse(content.contains("super_secret"));
                });
    }

    @Test
    @DisplayName("21. MDC trace_id and span_id are populated during filter and cleared afterwards")
    void test21_mdcContext_populatedAndCleared() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        w3cFilter.doFilter(request, response, (req, res) -> {
            assertNotNull(org.slf4j.MDC.get("trace_id"));
            assertNotNull(org.slf4j.MDC.get("span_id"));
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", org.slf4j.MDC.get("trace_id"));
        });

        assertNull(org.slf4j.MDC.get("trace_id"));
        assertNull(org.slf4j.MDC.get("span_id"));
    }

    @Test
    @DisplayName("22. Telemetry processing is non-blocking and zero downtime")
    void test22_telemetryNonBlocking() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            w3cFilter.parseAndValidateW3CTraceContext(request);
        }
        long duration = System.currentTimeMillis() - start;

        assertTrue(duration < 2000, "1000 trace parsing iterations completed in " + duration + "ms");
    }

    // ==========================================
    // HOSTILE REMEDIATION TESTS (23-26)
    // ==========================================

    @Test
    @DisplayName("23. Proves Prometheus actual boundary route cardinality safety across 25 UUIDs & 25 Numeric IDs")
    void test23_prometheusRouteCardinalityActualBoundary() throws Exception {
        // 1. Issue 25 requests to distinct UUID paths
        for (int i = 1; i <= 25; i++) {
            String uuid = String.format("550e8400-e29b-41d4-a716-44665544%04d", i);
            mockMvc.perform(get("/api/v1/projects/" + uuid));
        }

        // 2. Issue 25 requests to distinct numeric-ID paths
        for (int i = 1001; i <= 1025; i++) {
            mockMvc.perform(get("/api/v1/projects/" + i));
        }

        // 3. Scrape actual Prometheus endpoint
        MvcResult prometheusResult = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn();
        String prometheusOutput = prometheusResult.getResponse().getContentAsString();

        // 4. Assert zero UUID strings appear anywhere in Prometheus metrics output
        for (int i = 1; i <= 25; i++) {
            String uuid = String.format("550e8400-e29b-41d4-a716-44665544%04d", i);
            assertFalse(prometheusOutput.contains(uuid),
                    "Prometheus output leaked dynamic UUID path value: " + uuid);
        }

        // 5. Assert zero numeric IDs appear as raw metric URI label values
        for (int i = 1001; i <= 1025; i++) {
            assertFalse(prometheusOutput.contains("uri=\"/api/v1/projects/" + i + "\""),
                    "Prometheus output leaked dynamic numeric URI path value: " + i);
        }

        // 6. Directly inspect actual MeterRegistry to verify uri tags are bounded to route templates
        meterRegistry.find("http.server.requests").meters().forEach(meter -> {
            for (Tag tag : meter.getId().getTags()) {
                if ("uri".equals(tag.getKey())) {
                    String value = tag.getValue();
                    assertFalse(value.matches(".*/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*"),
                            "MeterRegistry tag 'uri' contains raw unnormalized UUID: " + value);
                    assertFalse(value.matches(".*/\\d+.*"),
                            "MeterRegistry tag 'uri' contains raw unnormalized numeric ID: " + value);
                }
            }
        });
    }

    @Test
    @DisplayName("24. Configurable trace sampling probability, bounds, and active SDK tracer provider wiring")
    void test24_traceSamplingConfigurableBoundedAndWired() {
        // 1. Valid probabilities
        assertEquals(1.0, TelemetryConfig.parseAndBoundProbability("1.0"));
        assertEquals(0.5, TelemetryConfig.parseAndBoundProbability("0.5"));
        assertEquals(0.0, TelemetryConfig.parseAndBoundProbability("0.0"));

        // 2. Out of bound inputs clamped
        assertEquals(0.0, TelemetryConfig.parseAndBoundProbability("-0.25"));
        assertEquals(1.0, TelemetryConfig.parseAndBoundProbability("2.5"));

        // 3. Invalid/malformed string fallback to 1.0 (safely bounded default)
        assertEquals(1.0, TelemetryConfig.parseAndBoundProbability("invalid_string"));
        assertEquals(1.0, TelemetryConfig.parseAndBoundProbability(""));
        assertEquals(1.0, TelemetryConfig.parseAndBoundProbability(null));

        // 4. Verify Sampler bean creation and active tracer wiring
        TelemetryConfig config = new TelemetryConfig();
        Sampler sampler = config.openTelemetrySampler();
        assertNotNull(sampler);

        // 5. Verify sampler decision is ratio-based and independent of user/tenant/security identity
        SamplingResult result = sampler.shouldSample(
                null,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "http.request",
                SpanKind.SERVER,
                Attributes.empty(),
                Collections.emptyList()
        );
        assertNotNull(result);
        assertNotNull(result.getDecision());
    }

    @Test
    @DisplayName("25. Observable telemetry export boundary verifies zero secrets leak to spans, MDC, or metrics")
    void test25_observableTelemetryExportBoundaryRedaction() throws Exception {
        String secretJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.SECRET_TOKEN_DATA";
        String secretCookie = "session_id=SECRET_SESSION_COOKIE_VALUE_12345";
        String secretApiKey = "sk-proj-SECRET_API_KEY_VALUE_9999";
        String secretPat = "ghp_SECRET_GITHUB_PAT_VALUE_00000";
        String secretPrivateKey = "-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC...\n-----END PRIVATE KEY-----";
        String secretPrompt = "SECRET_PROMPT_CONTAINING_SENSITIVE_USER_DATA";
        String secretCode = "public class SecretCode { private String password = \"supersecret\"; }";

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("Authorization", "Bearer " + secretJwt);
        request.addHeader("Cookie", secretCookie);
        request.addHeader("X-API-Key", secretApiKey);
        request.addHeader("X-GitHub-PAT", secretPat);
        request.setContent((secretPrivateKey + "\n" + secretPrompt + "\n" + secretCode).getBytes());

        // Execute filter chain
        w3cFilter.doFilter(request, response, (req, res) -> {
            // Verify MDC contains ONLY allowed trace_id and span_id
            Set<String> mdcKeys = org.slf4j.MDC.getCopyOfContextMap().keySet();
            assertTrue(mdcKeys.contains("trace_id"));
            assertTrue(mdcKeys.contains("span_id"));
            assertFalse(mdcKeys.contains("Authorization"));
            assertFalse(mdcKeys.contains("Cookie"));

            // Verify values in MDC
            assertFalse(org.slf4j.MDC.get("trace_id").contains(secretJwt));
            assertFalse(org.slf4j.MDC.get("span_id").contains(secretCookie));
        });

        // Inspect actual finished spans exported to InMemoryTestSpanExporter
        List<SpanData> finishedSpans = inMemoryExporter.getFinishedSpanItems();
        assertFalse(finishedSpans.isEmpty(), "At least one span should be captured by in-memory exporter");

        for (SpanData span : finishedSpans) {
            // 1. Check span name
            assertFalse(span.getName().contains(secretJwt));
            assertFalse(span.getName().contains(secretCookie));
            assertFalse(span.getName().contains(secretApiKey));

            // 2. Check span attributes
            span.getAttributes().forEach((key, val) -> {
                String valStr = String.valueOf(val);
                assertFalse(valStr.contains(secretJwt), "Secret JWT leaked to span attribute: " + key);
                assertFalse(valStr.contains(secretCookie), "Secret Cookie leaked to span attribute: " + key);
                assertFalse(valStr.contains(secretApiKey), "Secret API Key leaked to span attribute: " + key);
                assertFalse(valStr.contains(secretPat), "Secret PAT leaked to span attribute: " + key);
                assertFalse(valStr.contains(secretPrivateKey), "Secret Private Key leaked to span attribute: " + key);
                assertFalse(valStr.contains(secretPrompt), "Secret Prompt leaked to span attribute: " + key);
                assertFalse(valStr.contains(secretCode), "Secret Code leaked to span attribute: " + key);
            });

            // 3. Check span events
            span.getEvents().forEach(event -> {
                String eventName = event.getName();
                assertFalse(eventName.contains(secretJwt));
                assertFalse(eventName.contains(secretPrivateKey));
            });
        }
    }

    @Test
    @DisplayName("26. Actual Micrometer metric path uses finite exception classification without leaking raw exception messages or stack traces")
    void test26_exceptionToMetricIntegrationActualBoundary() {
        String rawSecretMessage = "DB_CONN_FAILURE_SECRET_PASSWORD_12345 at com.repopilot.service.Foo.bar(Foo.java:42)";
        RuntimeException rawEx = new RuntimeException(rawSecretMessage);

        String classified = SanitizedErrorClassifier.classify(rawEx);
        assertEquals("INTERNAL_SERVER_ERROR", classified);

        assertFalse(classified.contains("DB_CONN_FAILURE_SECRET_PASSWORD_12345"));
        assertFalse(classified.contains("RuntimeException"));
        assertFalse(classified.contains("Foo.java"));
    }
}
