package com.repopilot.testing.repository;

import com.repopilot.testing.entity.RepairIteration;
import com.repopilot.testing.entity.RepairIterationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepairIterationRepository extends JpaRepository<RepairIteration, UUID> {
    List<RepairIteration> findByTaskExecutionIdOrderByIterationNumberAsc(UUID taskExecutionId);
    Optional<RepairIteration> findByTaskExecutionIdAndIterationNumber(UUID taskExecutionId, Integer iterationNumber);
    long countByTaskExecutionId(UUID taskExecutionId);

    @Modifying
    @Query("UPDATE RepairIteration r SET r.status = :newStatus, r.completedAt = :completedAt, r.version = r.version + 1 " +
           "WHERE r.id = :id AND r.version = :expectedVersion AND r.status = :expectedStatus " +
           "AND r.status NOT IN (com.repopilot.testing.entity.RepairIterationStatus.VERIFIED, com.repopilot.testing.entity.RepairIterationStatus.REJECTED, com.repopilot.testing.entity.RepairIterationStatus.CANCELLED, com.repopilot.testing.entity.RepairIterationStatus.TIMED_OUT, com.repopilot.testing.entity.RepairIterationStatus.EXECUTION_FAILED, com.repopilot.testing.entity.RepairIterationStatus.AI_FAILURE, com.repopilot.testing.entity.RepairIterationStatus.VALIDATION_FAILED) " +
           "AND (SELECT e.fencingEpoch FROM TaskExecution e WHERE e.id = r.taskExecution.id) = :fencingEpoch")
    int updateStatusWithFencingAndVersion(@Param("id") UUID id, @Param("newStatus") RepairIterationStatus newStatus, @Param("completedAt") Instant completedAt, @Param("expectedVersion") Integer expectedVersion, @Param("expectedStatus") RepairIterationStatus expectedStatus, @Param("fencingEpoch") Long fencingEpoch);
}
