package com.repopilot.common.telemetry;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.concurrent.TimeoutException;

/**
 * Finite exception error type classifier for Micrometer and OpenTelemetry metrics.
 * Strictly prevents raw exception messages, stack traces, or user inputs from leaking into Prometheus metric labels.
 */
public class SanitizedErrorClassifier {

    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String CONFLICT = "CONFLICT";
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String TIMED_OUT = "TIMED_OUT";
    public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";
    public static final String UNKNOWN = "UNKNOWN";

    public static String classify(Throwable t) {
        if (t == null) {
            return "NONE";
        }
        Throwable root = getRootCause(t);

        if (root instanceof AuthenticationException) {
            return UNAUTHORIZED;
        }
        if (root instanceof AccessDeniedException) {
            return FORBIDDEN;
        }
        if (root instanceof MethodArgumentNotValidException || root instanceof IllegalArgumentException) {
            return BAD_REQUEST;
        }
        if (root instanceof TimeoutException) {
            return TIMED_OUT;
        }
        if (root.getClass().getName().contains("NotFound") || root.getClass().getName().contains("EntityNotFound")) {
            return NOT_FOUND;
        }
        if (root.getClass().getName().contains("OptimisticLocking") || root.getClass().getName().contains("Conflict")) {
            return CONFLICT;
        }

        return INTERNAL_SERVER_ERROR;
    }

    private static Throwable getRootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
