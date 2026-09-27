package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewMode;
import com.repopilot.review.entity.ReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CodeReviewResultDto(
    UUID reviewId,
    UUID projectId,
    UUID repositoryId,
    UUID taskId,
    UUID codeChangeId,
    ReviewMode reviewMode,
    ReviewStatus status,
    String summary,
    String overallRiskScore,
    String reviewContextHash,
    String modelIdentifier,
    Long executionTimeMs,
    Instant createdAt,
    Instant completedAt,
    int totalAiFindings,
    int acceptedFindingsCount,
    int rejectedFindingsCount,
    List<ValidatedFindingCandidate> findings
) {}
