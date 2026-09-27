package com.repopilot.review.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.review.entity.ReviewStatus;
import com.repopilot.review.transport.AiReviewerTransport;
import com.repopilot.review.transport.AiReviewerTransportException;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Implementation of Phase 13 ReviewOrchestrationService.
 * Enforces the transport + orchestration boundary without DB mutations, approvals, or external Git/GitHub calls.
 */
@Service
public class ReviewOrchestrationServiceImpl implements ReviewOrchestrationService {

    public static final String DEFAULT_PROMPT_VERSION = "p2026.1";
    public static final String DEFAULT_MODEL_IDENTIFIER = "gemini-2.5-pro";

    private final ReviewContextService reviewContextService;
    private final PreLlmSecretScanner preLlmSecretScanner;
    private final AiInputHashService aiInputHashService;
    private final AiReviewerTransport aiReviewerTransport;
    private final AiReviewerContractValidator contractValidator;
    private long timeoutMs = 30_000;

    @Autowired
    public ReviewOrchestrationServiceImpl(
            ReviewContextService reviewContextService,
            PreLlmSecretScanner preLlmSecretScanner,
            AiInputHashService aiInputHashService,
            AiReviewerTransport aiReviewerTransport,
            AiReviewerContractValidator contractValidator) {
        this.reviewContextService = reviewContextService;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
        this.aiInputHashService = aiInputHashService;
        this.aiReviewerTransport = aiReviewerTransport;
        this.contractValidator = contractValidator;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    @Override
    public AiReviewOrchestrationResult orchestrateReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            ReviewMode reviewMode,
            User requester) {

        long startTime = System.currentTimeMillis();
        final ReviewMode mode = reviewMode != null ? reviewMode : ReviewMode.CODE_CHANGE_REVIEW;

        // 1. Obtain Authoritative ReviewContext (throws FORBIDDEN / NOT_FOUND / REJECTED_CONTEXT_TOO_LARGE)
        ReviewContext context = reviewContextService.buildReviewContext(projectId, repositoryId, taskId, codeChangeId, mode, requester);

        // 2. Pre-LLM Secret Scanning / Redaction
        String rawDiff = context.unifiedDiff() != null ? context.unifiedDiff() : "";
        String redactedDiff = preLlmSecretScanner.redactSecrets(rawDiff);

        String rawPath = context.filePath() != null ? context.filePath() : "TASK_SCOPE";
        String sanitizedTitle = preLlmSecretScanner.redactSecrets(rawPath);
        List<String> fileList = List.of(sanitizedTitle);

        // 3. Construct Single-Source-of-Truth Canonical AI Payload & Compute ai_input_hash
        AiReviewPayload payload = new AiReviewPayload(
                projectId,
                repositoryId,
                taskId,
                codeChangeId,
                mode.name(),
                redactedDiff,
                sanitizedTitle,
                fileList,
                DEFAULT_PROMPT_VERSION,
                DEFAULT_MODEL_IDENTIFIER
        );
        String aiInputHash = aiInputHashService.computeAiInputHash(payload);

        // 4. Derive AiReviewerRequestDto directly from canonical payload (enforces Provenance Invariant)
        AiReviewerRequestDto requestDto = payload.toRequestDto(aiInputHash);

        // 5. Invoke Bounded Async Transport & Enforce Strict JSON Deserialization / Validation Boundary
        try {
            String rawJson = executeTransportWithTimeout(requestDto);

            // Strict JSON Deserialization Boundary
            AiReviewerResponseDto transportResponse = contractValidator.deserializeStrictJson(rawJson);
            contractValidator.validateResponse(transportResponse);

            long executionTime = System.currentTimeMillis() - startTime;
            ReviewStatus status = transportResponse.status() != null ? transportResponse.status() : ReviewStatus.COMPLETED;

            return new AiReviewOrchestrationResult(
                    codeChangeId,
                    projectId,
                    repositoryId,
                    taskId,
                    aiInputHash,
                    DEFAULT_PROMPT_VERSION,
                    DEFAULT_MODEL_IDENTIFIER,
                    mode,
                    status,
                    transportResponse.summary(),
                    transportResponse.overallRiskScore(),
                    transportResponse.findings() != null ? transportResponse.findings() : List.of(),
                    executionTime,
                    null
            );

        } catch (AiReviewerTransportException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String sanitizedReason = preLlmSecretScanner.redactSecrets(e.getCode() + ": " + e.getMessage());
            return AiReviewOrchestrationResult.failure(
                    codeChangeId, projectId, repositoryId, taskId,
                    aiInputHash, DEFAULT_PROMPT_VERSION, DEFAULT_MODEL_IDENTIFIER,
                    mode, sanitizedReason, executionTime
            );

        } catch (ApiException e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String sanitizedReason = preLlmSecretScanner.redactSecrets(e.getCode() + ": " + e.getMessage());
            return AiReviewOrchestrationResult.failure(
                    codeChangeId, projectId, repositoryId, taskId,
                    aiInputHash, DEFAULT_PROMPT_VERSION, DEFAULT_MODEL_IDENTIFIER,
                    mode, sanitizedReason, executionTime
            );

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            String sanitizedReason = preLlmSecretScanner.redactSecrets("AI_REVIEWER_TRANSPORT_FAILURE: " + e.getMessage());
            return AiReviewOrchestrationResult.failure(
                    codeChangeId, projectId, repositoryId, taskId,
                    aiInputHash, DEFAULT_PROMPT_VERSION, DEFAULT_MODEL_IDENTIFIER,
                    mode, sanitizedReason, executionTime
            );
        }
    }

    private String executeTransportWithTimeout(AiReviewerRequestDto requestDto) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> aiReviewerTransport.sendReviewRequestRawJson(requestDto));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw AiReviewerTransportException.timeout("AI reviewer transport request timed out after " + timeoutMs + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AiReviewerTransportException ate) {
                throw ate;
            } else if (cause instanceof ApiException ae) {
                throw ae;
            } else {
                throw AiReviewerTransportException.transportFailure(cause != null ? cause.getMessage() : "Transport execution error");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AiReviewerTransportException.transportFailure("Transport execution interrupted");
        } finally {
            executor.shutdownNow();
        }
    }
}
