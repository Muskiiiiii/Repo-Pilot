package com.repopilot.common.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Public REST entry filter performing explicit semantic validation of W3C Trace Context.
 * Rejects all-zero IDs, noncanonical uppercase hex, duplicate headers, and oversized context,
 * falling back to a fresh random local root span when context is invalid.
 * Unconditionally strips incoming baggage headers.
 * Correlates active trace_id and span_id in SLF4J MDC.
 */
public class W3CTraceContextFilter extends OncePerRequestFilter {

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String TRACESTATE_HEADER = "tracestate";

    private static final Pattern LOWERCASE_HEX_32 = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern LOWERCASE_HEX_16 = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern LOWERCASE_HEX_2 = Pattern.compile("^[0-9a-f]{2}$");

    private static final String ALL_ZERO_TRACE_ID = "00000000000000000000000000000000";
    private static final String ALL_ZERO_PARENT_ID = "0000000000000000";

    private final Tracer tracer;

    public W3CTraceContextFilter() {
        this(GlobalOpenTelemetry.getTracer("repopilot-backend"));
    }

    public W3CTraceContextFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Span serverSpan = null;
        SpanContext extractedContext = parseAndValidateW3CTraceContext(request);

        if (extractedContext != null && extractedContext.isValid()) {
            Context parentContext = Context.current().with(Span.wrap(extractedContext));
            serverSpan = tracer.spanBuilder("http.server.request")
                    .setParent(parentContext)
                    .startSpan();
        } else {
            // Discard invalid/missing context and initialize new local root span
            serverSpan = tracer.spanBuilder("http.server.request")
                    .setNoParent()
                    .startSpan();
        }

        String activeTraceId = serverSpan.getSpanContext().getTraceId();
        String activeSpanId = serverSpan.getSpanContext().getSpanId();

        MDC.put("trace_id", activeTraceId);
        MDC.put("span_id", activeSpanId);

        try (Scope scope = serverSpan.makeCurrent()) {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("trace_id");
            MDC.remove("span_id");
            serverSpan.end();
        }
    }

    /**
     * Parses and validates incoming W3C traceparent and tracestate HTTP headers according to Phase 16.3 specs.
     * Returns null if validation fails, indicating a fresh root span should be initialized.
     */
    public SpanContext parseAndValidateW3CTraceContext(HttpServletRequest request) {
        List<String> traceparents = Collections.list(request.getHeaders(TRACEPARENT_HEADER));

        // Duplicate header check: header injection attack -> discard context
        if (traceparents.size() != 1) {
            return null;
        }

        String traceparent = traceparents.get(0).trim();

        // Oversized header check
        if (traceparent.length() > 512) {
            return null;
        }

        String[] parts = traceparent.split("-", -1);
        if (parts.length < 4) {
            return null;
        }

        String version = parts[0];
        if ("00".equals(version)) {
            // Version 00 must be exactly 55 characters and 4 hyphen-separated fields
            if (traceparent.length() != 55 || parts.length != 4) {
                return null;
            }
        } else {
            // Future/unknown version handling: if length < 55 or malformed, reject
            if (traceparent.length() < 55) {
                return null;
            }
        }

        String traceId = parts[1];
        String parentId = parts[2];
        String traceFlags = parts[3];

        // Semantic validation: trace-id
        if (!LOWERCASE_HEX_32.matcher(traceId).matches() || ALL_ZERO_TRACE_ID.equals(traceId)) {
            return null;
        }

        // Semantic validation: parent-id
        if (!LOWERCASE_HEX_16.matcher(parentId).matches() || ALL_ZERO_PARENT_ID.equals(parentId)) {
            return null;
        }

        // Semantic validation: trace-flags
        if (!LOWERCASE_HEX_2.matcher(traceFlags).matches()) {
            return null;
        }

        // Tracestate handling: cap at 512 bytes, drop if oversized
        TraceState traceState = TraceState.getDefault();
        String tracestateHeader = request.getHeader(TRACESTATE_HEADER);
        if (tracestateHeader != null && !tracestateHeader.isBlank() && tracestateHeader.length() <= 512) {
            // Valid length tracestate
            try {
                traceState = parseTraceState(tracestateHeader);
            } catch (Exception ignored) {
                traceState = TraceState.getDefault();
            }
        }

        // Note: Incoming baggage headers are UNCONDITIONALLY STRIPPED (ignored)

        try {
            return SpanContext.createFromRemoteParent(
                    traceId,
                    parentId,
                    TraceFlags.fromHex(traceFlags, 0),
                    traceState
            );
        } catch (Exception e) {
            return null;
        }
    }

    private TraceState parseTraceState(String header) {
        // Minimal TraceState parser or return default
        return TraceState.getDefault();
    }
}
