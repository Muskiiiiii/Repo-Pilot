package com.repopilot.review.dto;

public record FindingSummaryDto(
        long totalCount,
        long criticalCount,
        long highCount,
        long mediumCount,
        long lowCount,
        long infoCount
) {}
