package com.repopilot.testing.repository;

import com.repopilot.testing.entity.RepairLoopIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepairLoopIdempotencyRecordRepository extends JpaRepository<RepairLoopIdempotencyRecord, UUID> {

    Optional<RepairLoopIdempotencyRecord> findByUserIdAndProjectIdAndExecutionIdAndOperationAndIdempotencyKey(
            UUID userId, UUID projectId, UUID executionId, String operation, String idempotencyKey);
}
