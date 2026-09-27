package com.repopilot.testing.repository;

import com.repopilot.testing.entity.SandboxJobExecution;
import com.repopilot.testing.entity.SandboxJobStatus;
import com.repopilot.testing.entity.SandboxWorkerNode;
import jakarta.persistence.LockModeType;
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
public interface SandboxJobExecutionRepository extends JpaRepository<SandboxJobExecution, UUID> {

    Optional<SandboxJobExecution> findByTaskExecutionIdAndCommandType(UUID taskExecutionId, String commandType);

    List<SandboxJobExecution> findByProjectIdAndRepositoryId(UUID projectId, UUID repositoryId);

    List<SandboxJobExecution> findByStatus(SandboxJobStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM SandboxJobExecution j WHERE j.id = :jobId")
    Optional<SandboxJobExecution> findByIdWithLock(@Param("jobId") UUID jobId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SandboxJobExecution j SET j.assignedWorkerNode = :workerNode, j.leaseToken = :newLeaseToken, j.fencingVersion = j.fencingVersion + 1, j.status = :newStatus, j.startedAt = COALESCE(j.startedAt, :now), j.leaseExpiresAt = :newLeaseExpiresAt WHERE j.id = :jobId AND (j.status = com.repopilot.testing.entity.SandboxJobStatus.PENDING OR j.status = com.repopilot.testing.entity.SandboxJobStatus.LEASE_EXPIRED OR ((j.status = com.repopilot.testing.entity.SandboxJobStatus.CLAIMED OR j.status = com.repopilot.testing.entity.SandboxJobStatus.RUNNING) AND j.leaseExpiresAt < :now))")
    int atomicClaimJob(@Param("jobId") UUID jobId,
                       @Param("workerNode") SandboxWorkerNode workerNode,
                       @Param("newLeaseToken") UUID newLeaseToken,
                       @Param("newStatus") SandboxJobStatus newStatus,
                       @Param("newLeaseExpiresAt") Instant newLeaseExpiresAt,
                       @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SandboxJobExecution j SET j.leaseExpiresAt = :newLeaseExpiresAt WHERE j.id = :jobId AND j.assignedWorkerNode.id = :workerNodeId AND j.leaseToken = :leaseToken AND j.fencingVersion = :fencingVersion AND j.leaseExpiresAt > :now AND (j.status = com.repopilot.testing.entity.SandboxJobStatus.CLAIMED OR j.status = com.repopilot.testing.entity.SandboxJobStatus.RUNNING)")
    int atomicRenewLease(@Param("jobId") UUID jobId,
                         @Param("workerNodeId") UUID workerNodeId,
                         @Param("leaseToken") UUID leaseToken,
                         @Param("fencingVersion") Long fencingVersion,
                         @Param("newLeaseExpiresAt") Instant newLeaseExpiresAt,
                         @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SandboxJobExecution j SET j.status = :newStatus WHERE j.id = :jobId AND j.assignedWorkerNode.id = :workerNodeId AND j.leaseToken = :leaseToken AND j.fencingVersion = :fencingVersion AND j.leaseExpiresAt > :now AND (j.status = com.repopilot.testing.entity.SandboxJobStatus.CLAIMED OR j.status = com.repopilot.testing.entity.SandboxJobStatus.RUNNING)")
    int atomicUpdateStatus(@Param("jobId") UUID jobId,
                           @Param("workerNodeId") UUID workerNodeId,
                           @Param("leaseToken") UUID leaseToken,
                           @Param("fencingVersion") Long fencingVersion,
                           @Param("newStatus") SandboxJobStatus newStatus,
                           @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SandboxJobExecution j SET j.status = com.repopilot.testing.entity.SandboxJobStatus.COMPLETED, j.exitCode = :exitCode, j.containerId = :containerId, j.completedAt = :now, j.leaseExpiresAt = NULL WHERE j.id = :jobId AND j.assignedWorkerNode.id = :workerNodeId AND j.leaseToken = :leaseToken AND j.fencingVersion = :fencingVersion AND j.leaseExpiresAt > :now AND (j.status = com.repopilot.testing.entity.SandboxJobStatus.CLAIMED OR j.status = com.repopilot.testing.entity.SandboxJobStatus.RUNNING)")
    int atomicCompleteJob(@Param("jobId") UUID jobId,
                          @Param("workerNodeId") UUID workerNodeId,
                          @Param("leaseToken") UUID leaseToken,
                          @Param("fencingVersion") Long fencingVersion,
                          @Param("exitCode") Integer exitCode,
                          @Param("containerId") String containerId,
                          @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SandboxJobExecution j SET j.status = com.repopilot.testing.entity.SandboxJobStatus.FAILED, j.exitCode = :exitCode, j.completedAt = :now, j.leaseExpiresAt = NULL WHERE j.id = :jobId AND j.assignedWorkerNode.id = :workerNodeId AND j.leaseToken = :leaseToken AND j.fencingVersion = :fencingVersion AND j.leaseExpiresAt > :now AND (j.status = com.repopilot.testing.entity.SandboxJobStatus.CLAIMED OR j.status = com.repopilot.testing.entity.SandboxJobStatus.RUNNING)")
    int atomicFailJob(@Param("jobId") UUID jobId,
                      @Param("workerNodeId") UUID workerNodeId,
                      @Param("leaseToken") UUID leaseToken,
                      @Param("fencingVersion") Long fencingVersion,
                      @Param("exitCode") Integer exitCode,
                      @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SandboxJobExecution j SET j.status = com.repopilot.testing.entity.SandboxJobStatus.PENDING, j.assignedWorkerNode = NULL, j.leaseExpiresAt = NULL WHERE j.id = :jobId AND j.assignedWorkerNode.id = :workerNodeId AND j.leaseToken = :leaseToken AND j.fencingVersion = :fencingVersion AND j.leaseExpiresAt > :now AND (j.status = com.repopilot.testing.entity.SandboxJobStatus.CLAIMED OR j.status = com.repopilot.testing.entity.SandboxJobStatus.RUNNING)")
    int atomicReleaseJob(@Param("jobId") UUID jobId,
                         @Param("workerNodeId") UUID workerNodeId,
                         @Param("leaseToken") UUID leaseToken,
                         @Param("fencingVersion") Long fencingVersion,
                         @Param("now") Instant now);
}
