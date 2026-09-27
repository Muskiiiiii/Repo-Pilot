package com.repopilot.review.service;

import com.repopilot.review.dto.SecurityPolicyComplianceDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

/**
 * Service contract for Phase 13 Slice 11 Automated Security Policy & Compliance Evaluation Engine.
 */
public interface SecurityPolicyComplianceService {

    SecurityPolicyComplianceDto evaluatePolicyCompliance(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            User requester
    );
}
