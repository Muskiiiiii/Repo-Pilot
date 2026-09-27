package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewFinding;

import java.util.UUID;

public record CanonicalFindingInput(
    UUID id,
    String category,
    String contentHash,
    String cweId,
    String description,
    Integer endLine,
    String filePath,
    String findingType,
    String fingerprint,
    String severity,
    String side,
    Integer startLine,
    String status,
    String title
) {
    public static CanonicalFindingInput fromEntity(ReviewFinding f) {
        if (f == null) return null;
        return new CanonicalFindingInput(
            f.getId(),
            f.getCategory(),
            f.getContentHash(),
            f.getCweId(),
            f.getDescription(),
            f.getEndLine(),
            f.getFilePath(),
            f.getFindingType() != null ? f.getFindingType().name() : null,
            f.getFingerprint(),
            f.getSeverity() != null ? f.getSeverity().name() : null,
            f.getSide() != null ? f.getSide().name() : null,
            f.getStartLine(),
            f.getStatus() != null ? f.getStatus().name() : null,
            f.getTitle()
        );
    }
}
