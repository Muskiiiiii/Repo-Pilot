package com.repopilot.task.repository;

import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
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
public interface TaskExecutionRepository extends JpaRepository<TaskExecution, UUID> {

    List<TaskExecution> findByTask(Task task);

    List<TaskExecution> findByTaskId(UUID taskId);

    Optional<TaskExecution> findByIdAndTaskId(UUID id, UUID taskId);

    Optional<TaskExecution> findByIdAndTaskIdAndRepositoryIdAndProjectId(UUID id, UUID taskId, UUID repositoryId, UUID projectId);

    Optional<TaskExecution> findFirstByTaskIdOrderByExecutionNumberDesc(UUID taskId);

    boolean existsByTaskIdAndStatusIn(UUID taskId, List<ExecutionStatus> statuses);

    @Modifying
    @Query("UPDATE TaskExecution e SET e.fencingEpoch = e.fencingEpoch + 1, e.ownerToken = :ownerToken, e.version = e.version + 1 " +
           "WHERE e.id = :id AND e.status NOT IN (com.repopilot.task.entity.ExecutionStatus.COMPLETED, com.repopilot.task.entity.ExecutionStatus.CANCELLED, com.repopilot.task.entity.ExecutionStatus.FAILED, com.repopilot.task.entity.ExecutionStatus.TIMED_OUT)")
    int claimLeadershipAndIncrementEpoch(@Param("id") UUID id, @Param("ownerToken") String ownerToken);

    @Modifying
    @Query("UPDATE TaskExecution e SET e.status = :newStatus, e.completedAt = :completedAt, e.version = e.version + 1 " +
           "WHERE e.id = :id AND e.version = :expectedVersion AND e.fencingEpoch = :fencingEpoch " +
           "AND e.cancellationRequested = false " +
           "AND e.status NOT IN (com.repopilot.task.entity.ExecutionStatus.COMPLETED, com.repopilot.task.entity.ExecutionStatus.CANCELLED, com.repopilot.task.entity.ExecutionStatus.FAILED, com.repopilot.task.entity.ExecutionStatus.TIMED_OUT)")
    int updateStatusWithFencingAndVersion(@Param("id") UUID id, @Param("newStatus") ExecutionStatus newStatus, @Param("completedAt") Instant completedAt, @Param("expectedVersion") Integer expectedVersion, @Param("fencingEpoch") Long fencingEpoch);

    @Modifying
    @Query("UPDATE TaskExecution e SET e.recoveryAttempts = e.recoveryAttempts + 1, e.version = e.version + 1 " +
           "WHERE e.id = :id AND e.version = :expectedVersion AND e.fencingEpoch = :fencingEpoch " +
           "AND e.recoveryAttempts < 5 " +
           "AND e.status NOT IN (com.repopilot.task.entity.ExecutionStatus.COMPLETED, com.repopilot.task.entity.ExecutionStatus.CANCELLED, com.repopilot.task.entity.ExecutionStatus.FAILED, com.repopilot.task.entity.ExecutionStatus.TIMED_OUT)")
    int incrementRecoveryAttemptsWithFencingAndCeiling(@Param("id") UUID id, @Param("expectedVersion") Integer expectedVersion, @Param("fencingEpoch") Long fencingEpoch);

    @Modifying
    @Query("UPDATE TaskExecution e SET e.cancellationRequested = true, e.status = com.repopilot.task.entity.ExecutionStatus.CANCELLED, e.completedAt = :completedAt, e.version = e.version + 1 " +
           "WHERE e.id = :id AND e.status NOT IN (com.repopilot.task.entity.ExecutionStatus.COMPLETED, com.repopilot.task.entity.ExecutionStatus.CANCELLED, com.repopilot.task.entity.ExecutionStatus.FAILED, com.repopilot.task.entity.ExecutionStatus.TIMED_OUT)")
    int requestCancellationAtomic(@Param("id") UUID id, @Param("completedAt") Instant completedAt);

    @Query("SELECT e FROM TaskExecution e WHERE e.status IN (com.repopilot.task.entity.ExecutionStatus.RUNNING, com.repopilot.task.entity.ExecutionStatus.PREPARING) AND e.startedAt < :staleThreshold AND e.recoveryAttempts < 5 ORDER BY e.startedAt ASC")
    List<TaskExecution> findStaleExecutions(@Param("staleThreshold") Instant staleThreshold);
}
