package com.repopilot.review.dto;

import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.review.entity.ReviewStatus;

import java.util.List;
import java.util.UUID;

/**
 * Bounded internal review orchestration result.
 * Preserves provenance (`ai_input_hash`, `promptVersion`, `modelIdentifier`) and validated review output.
 */
public record AiReviewOrchestrationResult(
        UUID codeChangeId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        String aiInputHash,
        String promptVersion,
        String modelIdentifier,
        ReviewMode reviewMode,
        ReviewStatus status,
        String summary,
        FindingSeverity overallRiskScore,
        List<AiReviewerFindingDto> findings,
        long executionTimeMs,
        String failureReason
) {
    public static AiReviewOrchestrationResult failure(
            UUID codeChangeId, UUID projectId, UUID repositoryId, UUID taskId,
            String aiInputHash, String promptVersion, String modelIdentifier,
            ReviewMode reviewMode, String failureReason, long executionTimeMs) {
        return new AiReviewOrchestrationResult(
                codeChangeId, projectId, repositoryId, taskId,
                aiInputHash, promptVersion, modelIdentifier,
                reviewMode, ReviewStatus.FAILED, "Review orchestration failed",
                FindingSeverity.HIGH, List.of(), executionTimeMs, failureReason
        );
    }
}
