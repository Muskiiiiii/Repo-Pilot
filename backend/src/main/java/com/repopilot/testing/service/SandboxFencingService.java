package com.repopilot.testing.service;

import com.repopilot.testing.entity.SandboxJobStatus;
import com.repopilot.testing.model.SandboxOwnershipClaim;

import java.util.UUID;

public interface SandboxFencingService {

    SandboxOwnershipClaim claimJob(UUID jobId, UUID workerNodeId, int leaseDurationSeconds);

    SandboxOwnershipClaim renewLease(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, int leaseDurationSeconds);

    void updateStatus(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, SandboxJobStatus newStatus);

    void completeJob(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, Integer exitCode, String containerId);

    void failJob(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, Integer exitCode);

    void releaseJob(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion);

    boolean validateOwnership(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion);
}
