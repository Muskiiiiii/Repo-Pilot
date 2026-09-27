package com.repopilot.common.telemetry;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Spring RestTemplate client HTTP request interceptor for W3C trace context propagation.
 * Injects active OpenTelemetry traceparent and tracestate headers into outbound Spring -> FastAPI requests.
 * Explicitly excludes baggage and authentication/security credentials from telemetry propagation.
 */
public class W3CTraceContextClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final TextMapSetter<HttpRequest> SETTER = (request, key, value) -> {
        if (request != null && request.getHeaders() != null && key != null && value != null) {
            String headerKey = key.toLowerCase();
            if ("traceparent".equals(headerKey) || "tracestate".equals(headerKey)) {
                request.getHeaders().set(key, value);
            }
        }
    };

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Context current = Context.current();
        W3CTraceContextPropagator.getInstance().inject(current, request, SETTER);
        return execution.execute(request, body);
    }
}
