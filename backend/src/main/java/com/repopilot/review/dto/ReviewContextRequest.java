package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewMode;

import java.util.UUID;

public record ReviewContextRequest(
    UUID projectId,
    UUID repositoryId,
    UUID taskId,
    UUID codeChangeId,
    ReviewMode reviewMode
) {}
