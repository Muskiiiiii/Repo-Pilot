package com.repopilot.approval.repository;

import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HumanApprovalRepository extends JpaRepository<HumanApproval, UUID> {

    Optional<HumanApproval> findByCodeChangeIdAndApprovalVersion(UUID codeChangeId, Integer approvalVersion);

    Optional<HumanApproval> findByIdempotencyKey(String idempotencyKey);

    List<HumanApproval> findByCodeChangeId(UUID codeChangeId);

    List<HumanApproval> findByTaskId(UUID taskId);

    @Query("SELECT a FROM HumanApproval a WHERE a.codeChange.id = :codeChangeId AND a.status = :status")
    List<HumanApproval> findByCodeChangeIdAndStatus(@Param("codeChangeId") UUID codeChangeId, @Param("status") ApprovalStatus status);
}
