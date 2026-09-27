package com.repopilot.review.service;

import com.repopilot.review.dto.CodeReviewResultDto;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.user.entity.User;

import java.util.UUID;

/**
 * Phase 13 Slice 4 Persistent Review Lifecycle Service.
 * Manages the durable lifecycle:
 * Request -> Persisted Review (PENDING) -> Execution (RUNNING) -> Slice 3 Orchestration -> TOCTOU Verification -> Persistent Findings -> Terminal State.
 */
public interface PersistentReviewLifecycleService {

    /**
     * Creates a new persistent review record in PENDING state or reuses existing idempotent review.
     */
    CodeReview createReviewRecord(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            ReviewMode reviewMode,
            User requester
    );

    /**
     * Executes persistent review lifecycle with TOCTOU protection, atomic finding persistence, and state transitions.
     */
    CodeReviewResultDto executePersistentReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            ReviewMode reviewMode,
            User requester
    );

    /**
     * Retrieves existing review result by ID with IDOR protection.
     */
    CodeReviewResultDto getReviewResult(UUID reviewId, UUID projectId, User requester);
}
