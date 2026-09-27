package com.repopilot.review.dto;

import java.util.UUID;

public record EvidenceRef(
    String sourceType,
    UUID projectId,
    UUID repositoryId,
    UUID taskId,
    UUID codeChangeId,
    String filePath,
    String originalOrProposed,
    Integer startLine,
    Integer endLine,
    String contentHash,
    String sourceSnapshotHash,
    String chunkId,
    String authorizationScope
) {}
