package com.repopilot.llm.dto;

import com.repopilot.llm.provider.dto.LlmProviderResponse;
import com.repopilot.llm.provider.exception.LlmProviderException;
import com.repopilot.llm.provider.model.LlmProviderFailureReason;

public class LlmExecutionOutcome {

    public enum Status {
        SUCCEEDED,
        FAILED,
        UNKNOWN_OUTCOME
    }

    public enum SafetyClassification {
        /**
         * Safe: Fencing check failed prior to network dispatch (lease expired or reclaimed). Request never crossed the wire.
         */
        CONFIRMED_NOT_DISPATCHED,

        /**
         * Safe: Provider explicitly rejected the request with a provider-documented semantic guarantee that execution did not occur
         * (e.g. Authentication failure, Unsupported capability, explicit security rejection pre-handshake).
         * NOTE: Generic HTTP 4xx/5xx errors do NOT automatically qualify as confirmed pre-execution rejections.
         */
        CONFIRMED_PROVIDER_REJECTION,

        /**
         * Terminal: Execution succeeded and response payload was successfully validated.
         */
        SUCCEEDED,

        /**
         * Uncertain: Post-dispatch timeout, transport connection drop, or generic provider error (HTTP 500 / internal error).
         * The external side-effect is uncertain (request may have reached or executed at provider).
         */
        UNKNOWN_EXTERNAL_OUTCOME,

        /**
         * Fenced out: Worker lost database lease/fencing ownership (V2 < V3). Finalization attempt rejected by DB.
         */
        STALE_INTERNAL_RESULT
    }

    private final Status status;
    private final LlmProviderResponse providerResponse;
    private final LlmProviderFailureReason failureReason;
    private final String errorMessage;
    private final SafetyClassification safetyClassification;

    private LlmExecutionOutcome(Status status,
                                LlmProviderResponse providerResponse,
                                LlmProviderFailureReason failureReason,
                                String errorMessage,
                                SafetyClassification safetyClassification) {
        this.status = status;
        this.providerResponse = providerResponse;
        this.failureReason = failureReason;
        this.errorMessage = errorMessage;
        this.safetyClassification = safetyClassification;
    }

    public static LlmExecutionOutcome succeeded(LlmProviderResponse response) {
        return new LlmExecutionOutcome(Status.SUCCEEDED, response, null, null, SafetyClassification.SUCCEEDED);
    }

    public static LlmExecutionOutcome failed(LlmProviderException ex) {
        LlmProviderFailureReason reason = ex.getFailureReason();
        SafetyClassification classification;

        if (reason == LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH) {
            classification = SafetyClassification.CONFIRMED_NOT_DISPATCHED;
        } else if (reason == LlmProviderFailureReason.AUTHENTICATION_FAILURE ||
                   reason == LlmProviderFailureReason.UNSUPPORTED_CAPABILITY ||
                   reason == LlmProviderFailureReason.EXPLICIT_PROVIDER_REJECTION) {
            classification = SafetyClassification.CONFIRMED_PROVIDER_REJECTION;
        } else {
            // Generic 5xx (PROVIDER_INTERNAL_ERROR), generic transport drops, rate limits, malformed responses, etc.
            // MUST NOT be inferred as confirmed pre-execution rejections from status code or generic exceptions alone.
            classification = SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME;
        }

        return new LlmExecutionOutcome(Status.FAILED, null, reason, ex.getMessage(), classification);
    }

    public static LlmExecutionOutcome unknownOutcome(LlmProviderException ex) {
        return new LlmExecutionOutcome(Status.UNKNOWN_OUTCOME, null, LlmProviderFailureReason.TIMEOUT_AFTER_POSSIBLE_DISPATCH, ex.getMessage(), SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME);
    }

    public static LlmExecutionOutcome staleResult(String message) {
        return new LlmExecutionOutcome(Status.FAILED, null, LlmProviderFailureReason.TIMEOUT_BEFORE_DISPATCH, message, SafetyClassification.STALE_INTERNAL_RESULT);
    }

    public Status getStatus() {
        return status;
    }

    public LlmProviderResponse getProviderResponse() {
        return providerResponse;
    }

    public LlmProviderFailureReason getFailureReason() {
        return failureReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public SafetyClassification getSafetyClassification() {
        return safetyClassification;
    }

    /**
     * Determines whether this outcome permits automatic fallback / retry in Phase 16.4D.
     *
     * <p>Contract Semantics:
     * <ul>
     *   <li>CONFIRMED_NOT_DISPATCHED / CONFIRMED_PROVIDER_REJECTION: Always returns true (safe to retry/fallback on any provider).</li>
     *   <li>UNKNOWN_EXTERNAL_OUTCOME:
     *       <ul>
     *         <li>Returns false for providers without verified idempotency capability (providerIdempotencySupported == false).</li>
     *         <li>Returns true for idempotency-capable providers (providerIdempotencySupported == true).
     *             <b>SEMANTIC GUARANTEE:</b> This true value indicates ONLY that re-submission of the SAME provider request identity
     *             is permitted under the provider's idempotency contract. It DOES NOT guarantee that the first execution did not occur.</li>
     *       </ul>
     *   </li>
     *   <li>SUCCEEDED / STALE_INTERNAL_RESULT: Returns false.</li>
     * </ul>
     *
     * @param providerIdempotencySupported true if the provider capability supports request-level idempotency
     * @return true if retry/re-submission is permitted under the safety contract
     */
    public boolean isSafeForAutomaticFallback(boolean providerIdempotencySupported) {
        if (safetyClassification == SafetyClassification.CONFIRMED_NOT_DISPATCHED ||
            safetyClassification == SafetyClassification.CONFIRMED_PROVIDER_REJECTION) {
            return true;
        }
        if (safetyClassification == SafetyClassification.UNKNOWN_EXTERNAL_OUTCOME) {
            return providerIdempotencySupported;
        }
        return false;
    }
}
