package com.repopilot.review.dto;

import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingSide;
import com.repopilot.review.entity.FindingType;

public record ValidatedFindingCandidate(
    FindingType findingType,
    String category,
    String cweId,
    FindingSeverity severity,
    FindingConfidence confidence,
    String title,
    String description,
    String filePath,
    Integer startLine,
    Integer endLine,
    FindingSide side,
    String evidence,
    String remediation,
    String contentHash,
    String fingerprint,
    boolean isUnverifiedSecurityClaim
) {}
