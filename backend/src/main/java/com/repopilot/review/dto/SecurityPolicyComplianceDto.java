package com.repopilot.review.dto;

import com.repopilot.review.entity.PolicyComplianceStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO representing Security Policy Compliance Evaluation Posture for Phase 13 Slice 11.
 */
public record SecurityPolicyComplianceDto(
        UUID reviewId,
        UUID projectId,
        UUID repositoryId,
        UUID taskId,
        PolicyComplianceStatus complianceStatus,
        String policyVersion,
        String validatorVersion,
        long criticalUnmitigatedCount,
        long highUnmitigatedCount,
        long mediumUnmitigatedCount,
        long lowUnmitigatedCount,
        long mitigatedFindingsCount,
        long totalFindingsCount,
        boolean isExempted,
        String adminOverrideReason,
        UUID adminOverrideByUserId,
        Instant adminOverrideAt,
        boolean exceedsMaxFindingLimit,
        List<PolicyViolationDto> policyViolations
) {}
