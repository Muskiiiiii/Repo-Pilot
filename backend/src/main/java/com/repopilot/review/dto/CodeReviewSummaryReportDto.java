package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CodeReviewSummaryReportDto(
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        UUID codeChangeId,
        String codeChangeStatus,
        boolean isCodeChangeActive,
        boolean isAlreadyApproved,
        UUID reviewId,
        ReviewStatus reviewStatus,
        String overallRiskScore,
        boolean eligibleForApproval,
        boolean eligibleForNewApproval,
        String eligibilityReason,
        boolean isContextStale,
        String reviewContextHash,
        String currentContextHash,
        FindingSummaryDto findingsSummary,
        boolean hasAdminOverride,
        AdminReviewOverrideResponseDto adminOverride,
        int snippetCount,
        boolean snippetsCapped,
        List<ReviewFindingSnippetDto> snippets,
        Instant createdAt
) {}
