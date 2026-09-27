package com.repopilot.review.service;

import com.repopilot.review.dto.CodeReviewEligibilityResultDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface CodeReviewApprovalGateService {

    CodeReviewEligibilityResultDto evaluateReviewEligibilityForApproval(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID requestedReviewId,
            User requester
    );
}
