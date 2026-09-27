package com.repopilot.llm.repository;

import com.repopilot.llm.entity.LlmExecutionStatus;
import com.repopilot.llm.entity.LlmProviderAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderAttemptRepository extends JpaRepository<LlmProviderAttempt, UUID> {

    @Query(value = "SELECT * FROM llm_provider_attempts WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<LlmProviderAttempt> findByIdForUpdate(@Param("id") UUID id);

    Optional<LlmProviderAttempt> findByLogicalRequestIdAndAttemptNumber(UUID logicalRequestId, Integer attemptNumber);

    @Query("SELECT a FROM LlmProviderAttempt a JOIN FETCH a.logicalRequest lr JOIN FETCH lr.organization WHERE a.id = :id")
    Optional<LlmProviderAttempt> findByIdWithAssociations(@Param("id") UUID id);

    List<LlmProviderAttempt> findByLogicalRequestId(UUID logicalRequestId);

    List<LlmProviderAttempt> findByLogicalRequestIdOrderByAttemptNumberAsc(UUID logicalRequestId);

    Page<LlmProviderAttempt> findByLogicalRequestIdOrderByAttemptNumberAsc(UUID logicalRequestId, Pageable pageable);

    @Query("SELECT COALESCE(MAX(a.attemptNumber), 0) FROM LlmProviderAttempt a WHERE a.logicalRequest.id = :logicalRequestId")
    Integer findMaxAttemptNumberByLogicalRequestId(@Param("logicalRequestId") UUID logicalRequestId);

    Optional<LlmProviderAttempt> findByFallbackDecisionId(UUID fallbackDecisionId);

    @Modifying
    @Query("UPDATE LlmProviderAttempt a SET a.executionStatus = :claimedStatus, a.workerLeaseToken = :leaseToken, a.claimExpiresAt = :claimExpiresAt, a.fencingVersion = a.fencingVersion + 1 WHERE a.id = :attemptId AND (a.executionStatus = :createdStatus OR (a.claimExpiresAt IS NOT NULL AND a.claimExpiresAt <= :now))")
    int claimAttemptForExecution(@Param("attemptId") UUID attemptId,
                                 @Param("leaseToken") String leaseToken,
                                 @Param("claimExpiresAt") Instant claimExpiresAt,
                                 @Param("createdStatus") LlmExecutionStatus createdStatus,
                                 @Param("claimedStatus") LlmExecutionStatus claimedStatus,
                                 @Param("now") Instant now);

    @Modifying
    @Query("UPDATE LlmProviderAttempt a SET a.executionStatus = :targetStatus WHERE a.id = :attemptId AND a.workerLeaseToken = :leaseToken AND a.fencingVersion = :fencingVersion AND a.executionStatus = :currentStatus AND a.claimExpiresAt > :now")
    int authorizePreDispatchFencing(@Param("attemptId") UUID attemptId,
                                    @Param("leaseToken") String leaseToken,
                                    @Param("fencingVersion") Long fencingVersion,
                                    @Param("currentStatus") LlmExecutionStatus currentStatus,
                                    @Param("targetStatus") LlmExecutionStatus targetStatus,
                                    @Param("now") Instant now);

    @Modifying
    @Query("UPDATE LlmProviderAttempt a SET a.executionStatus = :newStatus, a.providerRequestId = :providerRequestId, a.promptTokens = :promptTokens, a.completionTokens = :completionTokens, a.totalTokens = :totalTokens, a.outputHash = :outputHash, a.endTime = :endTime, a.latencyMs = :latencyMs WHERE a.id = :attemptId AND a.workerLeaseToken = :leaseToken AND a.fencingVersion = :fencingVersion AND (a.executionStatus = :claimedStatus OR a.executionStatus = :executingStatus)")
    int finalizeAttemptSuccessWithFencing(@Param("attemptId") UUID attemptId,
                                          @Param("leaseToken") String leaseToken,
                                          @Param("fencingVersion") Long fencingVersion,
                                          @Param("claimedStatus") LlmExecutionStatus claimedStatus,
                                          @Param("executingStatus") LlmExecutionStatus executingStatus,
                                          @Param("newStatus") LlmExecutionStatus newStatus,
                                          @Param("providerRequestId") String providerRequestId,
                                          @Param("promptTokens") Integer promptTokens,
                                          @Param("completionTokens") Integer completionTokens,
                                          @Param("totalTokens") Integer totalTokens,
                                          @Param("outputHash") String outputHash,
                                          @Param("endTime") Instant endTime,
                                          @Param("latencyMs") Long latencyMs);

    @Modifying
    @Query("UPDATE LlmProviderAttempt a SET a.executionStatus = :newStatus, a.fallbackReason = :fallbackReason, a.endTime = :endTime WHERE a.id = :attemptId AND a.workerLeaseToken = :leaseToken AND a.fencingVersion = :fencingVersion AND (a.executionStatus = :claimedStatus OR a.executionStatus = :executingStatus)")
    int finalizeAttemptFailureWithFencing(@Param("attemptId") UUID attemptId,
                                          @Param("leaseToken") String leaseToken,
                                          @Param("fencingVersion") Long fencingVersion,
                                          @Param("claimedStatus") LlmExecutionStatus claimedStatus,
                                          @Param("executingStatus") LlmExecutionStatus executingStatus,
                                          @Param("newStatus") LlmExecutionStatus newStatus,
                                          @Param("fallbackReason") String fallbackReason,
                                          @Param("endTime") Instant endTime);

    @Query("SELECT a FROM LlmProviderAttempt a JOIN a.logicalRequest lr WHERE lr.organization.id = :orgId AND LOWER(a.providerId) = LOWER(:providerId) ORDER BY a.startTime DESC")
    List<LlmProviderAttempt> findRecentAttemptsByOrganizationAndProvider(@Param("orgId") UUID orgId, @Param("providerId") String providerId, Pageable pageable);
}
