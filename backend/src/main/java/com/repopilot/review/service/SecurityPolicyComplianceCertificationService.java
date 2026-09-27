package com.repopilot.review.service;

import com.repopilot.review.dto.CreatePolicyComplianceCertificateRequestDto;
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

/**
 * Service contract for Phase 13 Slice 12 Security Policy Compliance Certificate Issuance & Recovery.
 * Sole authority for certificate issuance, transaction recovery, and audit persistence.
 */
public interface SecurityPolicyComplianceCertificationService {

    SecurityPolicyComplianceCertificateDto issueCertificate(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            CreatePolicyComplianceCertificateRequestDto requestDto,
            User actor
    );

    SecurityPolicyComplianceCertificateDto getCertificate(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            UUID certificateId,
            User actor
    );

    SecurityPolicyComplianceCertificateDto getCertificateByIdempotencyKey(
            UUID projectId,
            UUID reviewId,
            String idempotencyKey,
            User actor
    );
}
