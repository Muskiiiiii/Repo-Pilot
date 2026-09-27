package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewStatus;

import java.util.UUID;

public record CodeReviewEligibilityResultDto(
        boolean eligible,
        String reason,
        UUID reviewId,
        ReviewStatus status,
        String reviewContextHash,
        boolean hasAdminOverride,
        int criticalFindingCount,
        int highFindingCount,
        int totalFindingCount
) {
    public static CodeReviewEligibilityResultDto ineligible(String reason) {
        return new CodeReviewEligibilityResultDto(false, reason, null, null, null, false, 0, 0, 0);
    }

    public static CodeReviewEligibilityResultDto ineligible(String reason, UUID reviewId, ReviewStatus status, String reviewContextHash, int criticalCount, int highCount, int totalCount) {
        return new CodeReviewEligibilityResultDto(false, reason, reviewId, status, reviewContextHash, false, criticalCount, highCount, totalCount);
    }

    public static CodeReviewEligibilityResultDto eligible(UUID reviewId, ReviewStatus status, String reviewContextHash, boolean hasAdminOverride, int criticalCount, int highCount, int totalCount) {
        String msg = hasAdminOverride ? "Eligible for approval (Admin Override active)" : "Eligible for approval (Passed AI Code Review)";
        return new CodeReviewEligibilityResultDto(true, msg, reviewId, status, reviewContextHash, hasAdminOverride, criticalCount, highCount, totalCount);
    }
}
