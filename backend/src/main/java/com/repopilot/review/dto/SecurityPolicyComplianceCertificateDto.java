package com.repopilot.review.dto;

import com.repopilot.review.entity.PolicyComplianceStatus;

import java.time.Instant;
import java.util.UUID;

public record SecurityPolicyComplianceCertificateDto(
    UUID certificateId,
    UUID reviewId,
    UUID projectId,
    UUID repositoryId,
    UUID taskId,
    UUID codeChangeId,
    PolicyComplianceStatus complianceStatus,
    String policyVersion,
    String reviewContextHash,
    String findingEvidenceHash,
    Integer criticalUnmitigatedCount,
    Integer highUnmitigatedCount,
    Integer mediumUnmitigatedCount,
    Integer lowUnmitigatedCount,
    Integer mitigatedFindingsCount,
    String adminOverrideReason,
    UUID adminOverrideByUserId,
    UUID issuedByUserId,
    String idempotencyKey,
    String certificateHash,
    Instant issuedAt
) {}
