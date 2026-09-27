package com.repopilot.review.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.ReviewStatus;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiReviewerResponseDto(
    ReviewStatus status,
    String summary,
    FindingSeverity overallRiskScore,
    List<AiReviewerFindingDto> findings
) {}
