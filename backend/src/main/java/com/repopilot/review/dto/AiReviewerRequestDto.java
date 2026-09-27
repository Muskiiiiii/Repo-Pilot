package com.repopilot.review.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiReviewerRequestDto(
    UUID projectId,
    UUID repositoryId,
    UUID taskId,
    UUID codeChangeId,
    String reviewMode,
    String redactedDiff,
    String sanitizedTitle,
    List<String> fileList,
    String promptVersion,
    String modelIdentifier,
    String aiInputHash
) {
    public AiReviewPayload toPayload() {
        return new AiReviewPayload(
            projectId,
            repositoryId,
            taskId,
            codeChangeId,
            reviewMode,
            redactedDiff,
            sanitizedTitle,
            fileList,
            promptVersion,
            modelIdentifier
        );
    }
}
