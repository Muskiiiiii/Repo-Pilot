package com.repopilot.common.telemetry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filter and utility class enforcing telemetry attribute redaction and route normalization.
 * Prevents sensitive headers, JWTs, secrets, or high-cardinality URIs from entering spans and Prometheus metrics.
 */
public class TelemetryRedactionFilter extends OncePerRequestFilter {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "jwt", "token", "secret", "password"
    );

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("/\\d+");

    public static boolean isSensitiveHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        return SENSITIVE_HEADERS.contains(headerName.toLowerCase().trim());
    }

    public static String sanitizeAttributeValue(String key, String value) {
        if (key != null && isSensitiveHeader(key)) {
            return "[REDACTED]";
        }
        if (value == null) {
            return null;
        }
        if (value.startsWith("Bearer ") || value.length() > 256) {
            return "[REDACTED]";
        }
        return value;
    }

    /**
     * Normalizes dynamic URI paths to prevent Prometheus metric label cardinality explosion.
     * Replaces UUIDs and numeric IDs with static placeholders like '{id}'.
     */
    public static String normalizeRoute(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        // Remove query parameters if any present
        int queryIdx = uri.indexOf('?');
        if (queryIdx != -1) {
            uri = uri.substring(0, queryIdx);
        }
        // Normalize UUIDs to {id}
        String normalized = UUID_PATTERN.matcher(uri).replaceAll("/{id}");
        // Normalize numeric IDs to {id}
        normalized = NUMERIC_ID_PATTERN.matcher(normalized).replaceAll("/{id}");
        return normalized;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, response);
    }
}
