package com.repopilot.testing.service;

import com.repopilot.testing.entity.SandboxJobExecution;
import com.repopilot.testing.entity.SandboxJobStatus;
import com.repopilot.testing.entity.SandboxWorkerNode;
import com.repopilot.testing.exception.JobClaimException;
import com.repopilot.testing.exception.StaleWorkerFencingException;
import com.repopilot.testing.model.SandboxOwnershipClaim;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import com.repopilot.testing.repository.SandboxWorkerNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SandboxFencingServiceImpl implements SandboxFencingService {

    private final SandboxJobExecutionRepository jobExecutionRepository;
    private final SandboxWorkerNodeRepository workerNodeRepository;

    public SandboxFencingServiceImpl(SandboxJobExecutionRepository jobExecutionRepository,
                                     SandboxWorkerNodeRepository workerNodeRepository) {
        this.jobExecutionRepository = jobExecutionRepository;
        this.workerNodeRepository = workerNodeRepository;
    }

    @Override
    @Transactional
    public SandboxOwnershipClaim claimJob(UUID jobId, UUID workerNodeId, int leaseDurationSeconds) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(workerNodeId, "workerNodeId must not be null");
        if (leaseDurationSeconds <= 0) {
            throw new IllegalArgumentException("leaseDurationSeconds must be greater than zero");
        }

        SandboxWorkerNode workerNode = workerNodeRepository.findById(workerNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Worker node not found: " + workerNodeId));

        UUID newLeaseToken = UUID.randomUUID();
        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plusSeconds(leaseDurationSeconds);

        int updatedCount = jobExecutionRepository.atomicClaimJob(
                jobId,
                workerNode,
                newLeaseToken,
                SandboxJobStatus.CLAIMED,
                leaseExpiresAt,
                now
        );

        if (updatedCount == 0) {
            Optional<SandboxJobExecution> jobOpt = jobExecutionRepository.findById(jobId);
            if (jobOpt.isEmpty()) {
                throw new IllegalArgumentException("Sandbox job execution not found: " + jobId);
            }
            throw new JobClaimException("Job " + jobId + " is currently owned by an active worker or cannot be claimed");
        }

        SandboxJobExecution updatedJob = jobExecutionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job disappeared after claim: " + jobId));

        return new SandboxOwnershipClaim(
                updatedJob.getId(),
                workerNodeId,
                updatedJob.getLeaseToken(),
                updatedJob.getFencingVersion(),
                updatedJob.getLeaseExpiresAt(),
                updatedJob.getStatus()
        );
    }

    @Override
    @Transactional
    public SandboxOwnershipClaim renewLease(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, int leaseDurationSeconds) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(workerNodeId, "workerNodeId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(fencingVersion, "fencingVersion must not be null");
        if (leaseDurationSeconds <= 0) {
            throw new IllegalArgumentException("leaseDurationSeconds must be greater than zero");
        }

        Instant now = Instant.now();
        Instant newLeaseExpiresAt = now.plusSeconds(leaseDurationSeconds);

        int updatedCount = jobExecutionRepository.atomicRenewLease(
                jobId,
                workerNodeId,
                leaseToken,
                fencingVersion,
                newLeaseExpiresAt,
                now
        );

        if (updatedCount == 0) {
            throw new StaleWorkerFencingException("Fenced lease renewal rejected for job " + jobId +
                    ": stale worker, invalid lease token, mismatched fencing version, or expired lease");
        }

        SandboxJobExecution updatedJob = jobExecutionRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job disappeared after renewal: " + jobId));

        return new SandboxOwnershipClaim(
                updatedJob.getId(),
                workerNodeId,
                updatedJob.getLeaseToken(),
                updatedJob.getFencingVersion(),
                updatedJob.getLeaseExpiresAt(),
                updatedJob.getStatus()
        );
    }

    @Override
    @Transactional
    public void updateStatus(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, SandboxJobStatus newStatus) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(workerNodeId, "workerNodeId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(fencingVersion, "fencingVersion must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        Instant now = Instant.now();
        int updatedCount = jobExecutionRepository.atomicUpdateStatus(
                jobId,
                workerNodeId,
                leaseToken,
                fencingVersion,
                newStatus,
                now
        );

        if (updatedCount == 0) {
            throw new StaleWorkerFencingException("Fenced status update rejected for job " + jobId +
                    ": stale worker, invalid lease token, mismatched fencing version, or expired lease");
        }
    }

    @Override
    @Transactional
    public void completeJob(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, Integer exitCode, String containerId) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(workerNodeId, "workerNodeId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(fencingVersion, "fencingVersion must not be null");

        Instant now = Instant.now();
        int updatedCount = jobExecutionRepository.atomicCompleteJob(
                jobId,
                workerNodeId,
                leaseToken,
                fencingVersion,
                exitCode,
                containerId,
                now
        );

        if (updatedCount == 0) {
            throw new StaleWorkerFencingException("Fenced completion rejected for job " + jobId +
                    ": stale worker, invalid lease token, mismatched fencing version, or expired lease");
        }
    }

    @Override
    @Transactional
    public void failJob(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, Integer exitCode) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(workerNodeId, "workerNodeId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(fencingVersion, "fencingVersion must not be null");

        Instant now = Instant.now();
        int updatedCount = jobExecutionRepository.atomicFailJob(
                jobId,
                workerNodeId,
                leaseToken,
                fencingVersion,
                exitCode,
                now
        );

        if (updatedCount == 0) {
            throw new StaleWorkerFencingException("Fenced failure update rejected for job " + jobId +
                    ": stale worker, invalid lease token, mismatched fencing version, or expired lease");
        }
    }

    @Override
    @Transactional
    public void releaseJob(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(workerNodeId, "workerNodeId must not be null");
        Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        Objects.requireNonNull(fencingVersion, "fencingVersion must not be null");

        Instant now = Instant.now();
        int updatedCount = jobExecutionRepository.atomicReleaseJob(
                jobId,
                workerNodeId,
                leaseToken,
                fencingVersion,
                now
        );

        if (updatedCount == 0) {
            throw new StaleWorkerFencingException("Fenced release rejected for job " + jobId +
                    ": stale worker, invalid lease token, mismatched fencing version, or expired lease");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean validateOwnership(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion) {
        if (jobId == null || workerNodeId == null || leaseToken == null || fencingVersion == null) {
            return false;
        }

        Instant now = Instant.now();
        Optional<SandboxJobExecution> jobOpt = jobExecutionRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return false;
        }

        SandboxJobExecution job = jobOpt.get();
        return job.getAssignedWorkerNode() != null &&
                workerNodeId.equals(job.getAssignedWorkerNode().getId()) &&
                leaseToken.equals(job.getLeaseToken()) &&
                fencingVersion.equals(job.getFencingVersion()) &&
                job.getLeaseExpiresAt() != null &&
                job.getLeaseExpiresAt().isAfter(now);
    }
}
