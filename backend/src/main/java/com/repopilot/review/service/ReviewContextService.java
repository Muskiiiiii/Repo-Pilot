package com.repopilot.review.service;

import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.ReviewContextRequest;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface ReviewContextService {

    ReviewContext buildReviewContext(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId, ReviewMode reviewMode, User requester);

    ReviewContext buildReviewContext(ReviewContextRequest request, User requester);

    boolean isContextStale(ReviewContext context);

    String computeReviewContextHash(ReviewContext context);

    void validatePath(String filePath);
}
