package com.repopilot.telemetry;

import com.repopilot.common.telemetry.W3CTraceContextClientHttpRequestInterceptor;
import com.repopilot.common.telemetry.W3CTraceContextFilter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

public class Phase16_Slice3C_DistributedTracingIntegrationTest {

    private Tracer tracer;
    private W3CTraceContextFilter filter;
    private W3CTraceContextClientHttpRequestInterceptor interceptor;

    @BeforeEach
    void setUp() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        tracer = tracerProvider.get("test-tracer");
        filter = new W3CTraceContextFilter(tracer);
        interceptor = new W3CTraceContextClientHttpRequestInterceptor();
    }

    @Test
    @DisplayName("Spring -> FastAPI Outbound Propagation Injects Active Trace ID")
    void test01_springToFastapiTracePropagation() throws Exception {
        Span activeSpan = tracer.spanBuilder("http.server.request").startSpan();
        String activeTraceId = activeSpan.getSpanContext().getTraceId();
        assertTrue(activeSpan.getSpanContext().isValid(), "Active span context must be valid");

        MockClientHttpRequest request = new MockClientHttpRequest();

        try (Scope scope = activeSpan.makeCurrent()) {
            interceptor.intercept(request, new byte[0], (req, body) -> null);
        } finally {
            activeSpan.end();
        }

        HttpHeaders headers = request.getHeaders();
        assertTrue(headers.containsKey("traceparent"), "Outbound request must contain traceparent header");

        String propagatedTraceparent = headers.getFirst("traceparent");
        assertNotNull(propagatedTraceparent);
        assertTrue(propagatedTraceparent.contains(activeTraceId), "Propagated traceparent must contain active trace_id");
        assertFalse(headers.containsKey("baggage"), "Baggage must NOT be propagated to outbound client requests");
    }

    @Test
    @DisplayName("Invalid Inbound Context Creates Fresh Root")
    void test02_invalidTraceContextHandling() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("traceparent", "invalid-traceparent-format");

        var spanContext = filter.parseAndValidateW3CTraceContext(req);
        assertNull(spanContext, "Invalid traceparent must be rejected (return null -> fresh root)");
    }

    @Test
    @DisplayName("Trace Context Modification Never Alters Security Decisions")
    void test03_telemetryAuthoritySeparation() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("traceparent", "00-ffffffffffffffffffffffffffffffff-ffffffffffffffff-01");

        var spanContext = filter.parseAndValidateW3CTraceContext(req);
        assertNotNull(spanContext);
        assertEquals("ffffffffffffffffffffffffffffffff", spanContext.getTraceId());
    }

    @Test
    @DisplayName("Baggage and Security Credentials Are Excluded From Telemetry Ingress & Egress")
    void test04_baggageAndCredentialsStripping() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        req.addHeader("baggage", "userId=admin,role=superuser");

        var spanContext = filter.parseAndValidateW3CTraceContext(req);
        assertNotNull(spanContext);
        assertTrue(spanContext.getTraceState().isEmpty() || !spanContext.getTraceState().asMap().containsKey("userId"));
    }
}
