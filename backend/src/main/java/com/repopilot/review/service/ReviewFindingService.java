package com.repopilot.review.service;

import com.repopilot.review.dto.ReviewFindingResponseDto;
import com.repopilot.review.dto.UpdateFindingDispositionRequestDto;
import com.repopilot.user.entity.User;

import java.util.List;
import java.util.UUID;

public interface ReviewFindingService {

    List<ReviewFindingResponseDto> getFindingsForReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            String severityFilter,
            User requester
    );

    ReviewFindingResponseDto updateFindingDisposition(
            UUID projectId,
            UUID taskId,
            UUID reviewId,
            UUID findingId,
            UpdateFindingDispositionRequestDto request,
            User requester
    );
}
