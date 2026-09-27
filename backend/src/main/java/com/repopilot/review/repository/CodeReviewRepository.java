package com.repopilot.review.repository;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.task.entity.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CodeReviewRepository extends JpaRepository<CodeReview, UUID> {

    List<CodeReview> findByTask(Task task);

    List<CodeReview> findByTaskId(UUID taskId);

    List<CodeReview> findByCodeChange(CodeChange codeChange);

    List<CodeReview> findByCodeChangeId(UUID codeChangeId);

    Optional<CodeReview> findByCodeChangeIdAndReviewContextHash(UUID codeChangeId, String reviewContextHash);

    Optional<CodeReview> findByCodeChangeIdAndReviewContextHashAndReviewModeAndAiInputHash(
            UUID codeChangeId,
            String reviewContextHash,
            com.repopilot.review.entity.ReviewMode reviewMode,
            String aiInputHash
    );

    @org.springframework.data.jpa.repository.Query("SELECT r FROM CodeReview r WHERE r.id = :id")
    Optional<CodeReview> findByIdWithLock(@org.springframework.data.repository.query.Param("id") UUID id);

    List<CodeReview> findByProjectIdAndRepositoryIdAndTaskIdAndCodeChangeId(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT r FROM CodeReview r
        WHERE r.codeChange.id = :codeChangeId
          AND r.task.id = :taskId
          AND r.repository.id = :repositoryId
          AND r.project.id = :projectId
          AND r.status = com.repopilot.review.entity.ReviewStatus.COMPLETED
        ORDER BY r.completedAt DESC, r.createdAt DESC, r.id DESC
    """)
    List<CodeReview> findCompletedReviewsForCodeChange(
            @org.springframework.data.repository.query.Param("projectId") UUID projectId,
            @org.springframework.data.repository.query.Param("repositoryId") UUID repositoryId,
            @org.springframework.data.repository.query.Param("taskId") UUID taskId,
            @org.springframework.data.repository.query.Param("codeChangeId") UUID codeChangeId,
            org.springframework.data.domain.Pageable pageable
    );

    List<CodeReview> findByProjectIdAndRepositoryIdAndTaskId(UUID projectId, UUID repositoryId, UUID taskId);

    @org.springframework.data.jpa.repository.Query("""
        SELECT r FROM CodeReview r
        WHERE r.status = :pendingStatus
           OR (r.status = :runningStatus AND r.leaseExpiresAt < :now)
        ORDER BY r.createdAt ASC
    """)
    List<CodeReview> findClaimableOrExpiredReviews(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("pendingStatus") com.repopilot.review.entity.ReviewStatus pendingStatus,
            @org.springframework.data.repository.query.Param("runningStatus") com.repopilot.review.entity.ReviewStatus runningStatus,
            org.springframework.data.domain.Pageable pageable
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        UPDATE CodeReview r
        SET r.status = :runningStatus,
            r.workerId = :workerId,
            r.leaseToken = :leaseToken,
            r.leaseExpiresAt = :leaseExpiresAt,
            r.claimedAt = :claimedAt,
            r.executionAttemptCount = r.executionAttemptCount + 1,
            r.summary = :summary
        WHERE r.id = :id
          AND (r.status = :pendingStatus
               OR (r.status = :runningStatus AND r.leaseExpiresAt < :claimedAt))
    """)
    int claimReviewAtomic(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("workerId") String workerId,
            @org.springframework.data.repository.query.Param("leaseToken") String leaseToken,
            @org.springframework.data.repository.query.Param("leaseExpiresAt") java.time.Instant leaseExpiresAt,
            @org.springframework.data.repository.query.Param("claimedAt") java.time.Instant claimedAt,
            @org.springframework.data.repository.query.Param("summary") String summary,
            @org.springframework.data.repository.query.Param("pendingStatus") com.repopilot.review.entity.ReviewStatus pendingStatus,
            @org.springframework.data.repository.query.Param("runningStatus") com.repopilot.review.entity.ReviewStatus runningStatus
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        UPDATE CodeReview r
        SET r.leaseExpiresAt = :newLeaseExpiresAt
        WHERE r.id = :id
          AND r.status = :runningStatus
          AND r.workerId = :workerId
          AND r.leaseToken = :leaseToken
    """)
    int renewLeaseAtomic(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("workerId") String workerId,
            @org.springframework.data.repository.query.Param("leaseToken") String leaseToken,
            @org.springframework.data.repository.query.Param("newLeaseExpiresAt") java.time.Instant newLeaseExpiresAt,
            @org.springframework.data.repository.query.Param("runningStatus") com.repopilot.review.entity.ReviewStatus runningStatus
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT r FROM CodeReview r
        WHERE r.id = :id
          AND r.status = :runningStatus
          AND r.workerId = :workerId
          AND r.leaseToken = :leaseToken
          AND r.leaseExpiresAt > :now
    """)
    Optional<CodeReview> findActiveClaimWithFencing(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("workerId") String workerId,
            @org.springframework.data.repository.query.Param("leaseToken") String leaseToken,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("runningStatus") com.repopilot.review.entity.ReviewStatus runningStatus
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        UPDATE CodeReview r
        SET r.status = :targetStatus,
            r.summary = :summary,
            r.completedAt = :completedAt,
            r.executionTimeMs = :executionTimeMs,
            r.overallRiskScore = :overallRiskScore
        WHERE r.id = :id
          AND r.status = :runningStatus
          AND r.workerId = :workerId
          AND r.leaseToken = :leaseToken
    """)
    int finalizeReviewFenced(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("workerId") String workerId,
            @org.springframework.data.repository.query.Param("leaseToken") String leaseToken,
            @org.springframework.data.repository.query.Param("targetStatus") com.repopilot.review.entity.ReviewStatus targetStatus,
            @org.springframework.data.repository.query.Param("summary") String summary,
            @org.springframework.data.repository.query.Param("overallRiskScore") String overallRiskScore,
            @org.springframework.data.repository.query.Param("executionTimeMs") Long executionTimeMs,
            @org.springframework.data.repository.query.Param("completedAt") java.time.Instant completedAt,
            @org.springframework.data.repository.query.Param("runningStatus") com.repopilot.review.entity.ReviewStatus runningStatus
    );

    @org.springframework.data.jpa.repository.Query("""
        SELECT r FROM CodeReview r
        WHERE r.project.id = :projectId
          AND r.repository.id = :repositoryId
          AND r.task.id = :taskId
          AND r.codeChange.id = :codeChangeId
          AND r.reviewContextHash = :reviewContextHash
          AND r.status = com.repopilot.review.entity.ReviewStatus.COMPLETED
        ORDER BY r.completedAt DESC
    """)
    List<CodeReview> findEligibleCompletedReviews(
            @org.springframework.data.repository.query.Param("projectId") UUID projectId,
            @org.springframework.data.repository.query.Param("repositoryId") UUID repositoryId,
            @org.springframework.data.repository.query.Param("taskId") UUID taskId,
            @org.springframework.data.repository.query.Param("codeChangeId") UUID codeChangeId,
            @org.springframework.data.repository.query.Param("reviewContextHash") String reviewContextHash
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
        UPDATE CodeReview r
        SET r.adminOverrideReason = :reason,
            r.adminOverrideByUser = :adminUser,
            r.adminOverrideAt = :now
        WHERE r.id = :id
          AND r.status = :completedStatus
          AND r.adminOverrideReason IS NULL
          AND r.adminOverrideByUser IS NULL
    """)
    int recordAdminOverrideAtomic(
            @org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("reason") String reason,
            @org.springframework.data.repository.query.Param("adminUser") com.repopilot.user.entity.User adminUser,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("completedStatus") com.repopilot.review.entity.ReviewStatus completedStatus
    );
}
