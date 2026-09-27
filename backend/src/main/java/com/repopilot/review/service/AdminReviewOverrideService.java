package com.repopilot.review.service;

import com.repopilot.review.dto.AdminReviewOverrideRequest;
import com.repopilot.review.dto.AdminReviewOverrideResponseDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface AdminReviewOverrideService {

    AdminReviewOverrideResponseDto overrideReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            AdminReviewOverrideRequest request,
            User adminUser
    );
}
