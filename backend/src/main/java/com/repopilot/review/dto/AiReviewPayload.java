package com.repopilot.review.dto;

import java.util.List;
import java.util.UUID;

public record AiReviewPayload(
    UUID projectId,
    UUID repositoryId,
    UUID taskId,
    UUID codeChangeId,
    String reviewMode,
    String redactedDiff,
    String sanitizedTitle,
    List<String> fileList,
    String promptVersion,
    String modelIdentifier
) {
    public AiReviewerRequestDto toRequestDto(String aiInputHash) {
        return new AiReviewerRequestDto(
            projectId,
            repositoryId,
            taskId,
            codeChangeId,
            reviewMode,
            redactedDiff,
            sanitizedTitle,
            fileList,
            promptVersion,
            modelIdentifier,
            aiInputHash
        );
    }
}
