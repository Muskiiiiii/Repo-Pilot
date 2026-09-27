package com.repopilot.review.dto;

import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingType;

import java.util.UUID;

public record ReviewFindingSnippetDto(
        UUID id,
        FindingType findingType,
        String category,
        String cweId,
        FindingSeverity severity,
        FindingConfidence confidence,
        String title,
        String description,
        String filePath,
        Integer startLine,
        Integer endLine
) {}
