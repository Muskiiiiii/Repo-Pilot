package com.repopilot.review.service;

import com.repopilot.review.dto.CodeReviewClaimDto;
import com.repopilot.review.dto.CodeReviewResultDto;
import com.repopilot.user.entity.User;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service contract for Phase 13 Slice 5 durable asynchronous review worker execution,
 * fencing token claims, heartbeat lease renewal, TOCTOU re-verification, and crash recovery.
 */
public interface ReviewWorkerExecutionService {

    /**
     * Claims a PENDING or expired RUNNING review for worker execution using an unpredictable fencing token.
     */
    Optional<CodeReviewClaimDto> claimNextAvailableReview(String workerId, Duration leaseDuration);

    /**
     * Claims a specific review ID if it is PENDING or expired RUNNING.
     */
    Optional<CodeReviewClaimDto> claimSpecificReview(UUID reviewId, String workerId, Duration leaseDuration);

    /**
     * Heartbeat lease renewal: Renews lease duration iff workerId and leaseToken remain authoritative in DB.
     */
    boolean renewLease(UUID reviewId, String workerId, String leaseToken, Duration extensionDuration);

    /**
     * Executes a claimed review with TOCTOU checks and fenced result persistence.
     */
    CodeReviewResultDto executeClaimedReview(CodeReviewClaimDto claim, User requester);

    /**
     * Discovers PENDING or expired RUNNING reviews directly from PostgreSQL (durable work discovery).
     */
    List<UUID> discoverClaimableReviewIds(int limit);
}
