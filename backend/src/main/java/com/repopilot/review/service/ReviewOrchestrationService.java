package com.repopilot.review.service;

import com.repopilot.review.dto.AiReviewOrchestrationResult;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.user.entity.User;

import java.util.UUID;

/**
 * Phase 13 Review Orchestration Service.
 * Manages the boundary flow:
 * Authoritative CodeChange -> ReviewContextService -> Pre-LLM Secret Scanner -> Canonical AI payload + ai_input_hash -> Controlled AI Reviewer Transport -> Strict response deserialization/validation -> Bounded internal review result.
 */
public interface ReviewOrchestrationService {

    /**
     * Orchestrates AI review execution for authorized project/repository/task/codeChange identity.
     *
     * @param projectId Project UUID
     * @param repositoryId Repository UUID
     * @param taskId Task UUID
     * @param codeChangeId CodeChange UUID (optional for TASK_IMPLEMENTATION_REVIEW)
     * @param reviewMode Review mode
     * @param requester Initiating user
     * @return Bounded internal review result holding provenance, status, risk score, and validated findings
     */
    AiReviewOrchestrationResult orchestrateReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            ReviewMode reviewMode,
            User requester
    );
}
