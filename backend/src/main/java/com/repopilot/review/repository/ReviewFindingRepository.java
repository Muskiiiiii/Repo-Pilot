package com.repopilot.review.repository;

import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.FindingStatus;
import com.repopilot.review.entity.ReviewFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewFindingRepository extends JpaRepository<ReviewFinding, UUID> {

    List<ReviewFinding> findByReview(CodeReview review);

    List<ReviewFinding> findByReviewId(UUID reviewId);

    @org.springframework.data.jpa.repository.Query("SELECT f FROM ReviewFinding f WHERE f.review.id IN :reviewIds")
    List<ReviewFinding> findByReviewIdIn(@org.springframework.data.repository.query.Param("reviewIds") List<UUID> reviewIds);

    @org.springframework.data.jpa.repository.Query("SELECT f FROM ReviewFinding f WHERE f.task.id = :taskId")
    List<ReviewFinding> findByTaskId(@org.springframework.data.repository.query.Param("taskId") UUID taskId);

    List<ReviewFinding> findByCodeChangeId(UUID codeChangeId);

    List<ReviewFinding> findByFingerprint(String fingerprint);

    Optional<ReviewFinding> findByReviewIdAndFingerprint(UUID reviewId, String fingerprint);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM review_findings WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<ReviewFinding> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM review_findings WHERE id = :findingId AND review_id = :reviewId FOR UPDATE", nativeQuery = true)
    Optional<ReviewFinding> findByIdAndReviewIdForUpdate(@org.springframework.data.repository.query.Param("findingId") UUID findingId, @org.springframework.data.repository.query.Param("reviewId") UUID reviewId);

    List<ReviewFinding> findByProjectIdAndStatus(UUID projectId, FindingStatus status);

    List<ReviewFinding> findByProjectIdAndRepositoryIdAndTaskId(UUID projectId, UUID repositoryId, UUID taskId);

    List<ReviewFinding> findByProjectIdAndRepositoryIdAndTaskIdAndCodeChangeId(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId);

    interface FindingSeverityCountProjection {
        com.repopilot.review.entity.FindingSeverity getSeverity();
        long getCount();
    }

    @org.springframework.data.jpa.repository.Query("""
        SELECT f.severity AS severity, COUNT(f) AS count
        FROM ReviewFinding f
        WHERE f.review.id = :reviewId
        GROUP BY f.severity
    """)
    List<FindingSeverityCountProjection> countFindingsBySeverityForReview(@org.springframework.data.repository.query.Param("reviewId") UUID reviewId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT f FROM ReviewFinding f
        WHERE f.review.id = :reviewId
        ORDER BY CASE f.severity
            WHEN com.repopilot.review.entity.FindingSeverity.CRITICAL THEN 1
            WHEN com.repopilot.review.entity.FindingSeverity.HIGH THEN 2
            WHEN com.repopilot.review.entity.FindingSeverity.MEDIUM THEN 3
            WHEN com.repopilot.review.entity.FindingSeverity.LOW THEN 4
            ELSE 5 END ASC, f.createdAt DESC, f.id DESC
    """)
    List<ReviewFinding> findBoundedSnippetsForReview(
            @org.springframework.data.repository.query.Param("reviewId") UUID reviewId,
            org.springframework.data.domain.Pageable pageable
    );

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(f) FROM ReviewFinding f WHERE f.review.id = :reviewId")
    long countTotalFindingsByReviewId(@org.springframework.data.repository.query.Param("reviewId") UUID reviewId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(f) FROM ReviewFinding f
        WHERE f.review.id = :reviewId
          AND f.severity = :severity
          AND f.status IN (com.repopilot.review.entity.FindingStatus.OPEN, com.repopilot.review.entity.FindingStatus.ACKNOWLEDGED)
    """)
    long countUnmitigatedFindingsBySeverityAndReviewId(
            @org.springframework.data.repository.query.Param("reviewId") UUID reviewId,
            @org.springframework.data.repository.query.Param("severity") com.repopilot.review.entity.FindingSeverity severity
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT COUNT(f) FROM ReviewFinding f
        WHERE f.review.id = :reviewId
          AND f.status IN (com.repopilot.review.entity.FindingStatus.RESOLVED, com.repopilot.review.entity.FindingStatus.FALSE_POSITIVE, com.repopilot.review.entity.FindingStatus.SUPPRESSED)
    """)
    long countMitigatedFindingsByReviewId(@org.springframework.data.repository.query.Param("reviewId") UUID reviewId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT f FROM ReviewFinding f
        WHERE f.review.id = :reviewId
          AND f.severity IN (com.repopilot.review.entity.FindingSeverity.CRITICAL, com.repopilot.review.entity.FindingSeverity.HIGH)
          AND f.status IN (com.repopilot.review.entity.FindingStatus.OPEN, com.repopilot.review.entity.FindingStatus.ACKNOWLEDGED)
        ORDER BY CASE f.severity
            WHEN com.repopilot.review.entity.FindingSeverity.CRITICAL THEN 1
            WHEN com.repopilot.review.entity.FindingSeverity.HIGH THEN 2
            ELSE 3 END ASC, f.createdAt DESC, f.id DESC
    """)
    List<ReviewFinding> findBlockingViolationsByReviewId(
            @org.springframework.data.repository.query.Param("reviewId") UUID reviewId,
            org.springframework.data.domain.Pageable pageable
    );
}
