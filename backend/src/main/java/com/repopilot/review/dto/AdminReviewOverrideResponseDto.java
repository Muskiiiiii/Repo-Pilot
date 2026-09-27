package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewStatus;

import java.time.Instant;
import java.util.UUID;

public record AdminReviewOverrideResponseDto(
        UUID reviewId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        ReviewStatus status,
        String adminOverrideReason,
        UUID adminOverrideByUserId,
        String adminOverrideByUserName,
        Instant adminOverrideAt
) {}
