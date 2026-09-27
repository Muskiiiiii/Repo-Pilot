package com.repopilot.llm.repository;

import com.repopilot.llm.entity.LlmFallbackDecision;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmFallbackDecisionRepository extends JpaRepository<LlmFallbackDecision, UUID> {

    Optional<LlmFallbackDecision> findBySourceAttemptIdAndDecisionSequence(UUID sourceAttemptId, Integer decisionSequence);

    @Query(value = "SELECT * FROM llm_fallback_decisions WHERE source_attempt_id = :sourceAttemptId AND decision_sequence = :decisionSequence FOR UPDATE", nativeQuery = true)
    Optional<LlmFallbackDecision> findBySourceAttemptIdAndDecisionSequenceForUpdate(@Param("sourceAttemptId") UUID sourceAttemptId, @Param("decisionSequence") Integer decisionSequence);

    @Query("SELECT COALESCE(MAX(d.decisionSequence), 0) FROM LlmFallbackDecision d WHERE d.sourceAttempt.id = :sourceAttemptId")
    Integer findMaxDecisionSequenceBySourceAttemptId(@Param("sourceAttemptId") UUID sourceAttemptId);

    Optional<LlmFallbackDecision> findByTargetAttemptId(UUID targetAttemptId);

    List<LlmFallbackDecision> findByLogicalRequestId(UUID logicalRequestId);

    List<LlmFallbackDecision> findByLogicalRequestIdOrderByDecisionSequenceAsc(UUID logicalRequestId);

    Page<LlmFallbackDecision> findByLogicalRequestIdOrderByDecisionSequenceAsc(UUID logicalRequestId, Pageable pageable);

    List<LlmFallbackDecision> findBySourceAttemptIdOrderByDecisionSequenceAsc(UUID sourceAttemptId);
}
