package com.repopilot.review.repository;

import com.repopilot.review.entity.ComplianceCertificate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ComplianceCertificateRepository extends JpaRepository<ComplianceCertificate, UUID> {

    Optional<ComplianceCertificate> findByIdempotencyKey(String idempotencyKey);

    Optional<ComplianceCertificate> findByProjectIdAndCodeReviewIdAndIdempotencyKey(
            UUID projectId, UUID codeReviewId, String idempotencyKey
    );

    Optional<ComplianceCertificate> findByCertificateHash(String certificateHash);

    Optional<ComplianceCertificate> findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
            UUID id, UUID projectId, UUID repositoryId, UUID taskId, UUID codeReviewId
    );

    java.util.List<ComplianceCertificate> findByTaskId(UUID taskId);
}
