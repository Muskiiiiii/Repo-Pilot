package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewMode;

import java.util.List;
import java.util.UUID;

public record ReviewContext(
    UUID projectId,
    UUID repositoryId,
    UUID taskId,
    UUID codeChangeId,
    ReviewMode reviewMode,
    String filePath,
    String operation,
    String originalContent,
    String proposedContent,
    String unifiedDiff,
    String originalHash,
    String proposedHash,
    String reviewContextHash,
    String contextVersion,
    boolean isStale,
    List<EvidenceRef> evidenceReferences
) {}
