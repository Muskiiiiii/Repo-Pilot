package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewMode;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing an active, fenced worker claim for a CodeReview execution.
 */
public record CodeReviewClaimDto(
        UUID reviewId,
        String workerId,
        String leaseToken,
        Instant leaseExpiresAt,
        Instant claimedAt,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        ReviewMode reviewMode
) {}
