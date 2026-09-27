package com.repopilot.review.service;

import com.repopilot.review.dto.CodeReviewResultDto;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface CodeReviewOrchestrationService {

    CodeReviewResultDto executeCodeReview(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId, ReviewMode reviewMode, String idempotencyKey, User requester);

    CodeReviewResultDto getCodeReviewResult(UUID reviewId, User requester);

    List<CodeReviewResultDto> getCodeReviewsForCodeChange(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId, User requester);

    List<CodeReviewResultDto> getCodeReviewsForTask(UUID projectId, UUID repositoryId, UUID taskId, User requester);
}
