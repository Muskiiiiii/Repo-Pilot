package com.repopilot.review.dto;

import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingStatus;

import java.util.UUID;

/**
 * DTO representing an unmitigated blocking policy violation finding for Phase 13 Slice 11.
 */
public record PolicyViolationDto(
        UUID findingId,
        String cweId,
        FindingSeverity severity,
        FindingStatus status,
        FindingConfidence confidence,
        String title,
        String description,
        String filePath,
        Integer startLine,
        Integer endLine
) {}
