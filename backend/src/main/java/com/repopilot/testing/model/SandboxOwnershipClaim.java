package com.repopilot.testing.model;

import com.repopilot.testing.entity.SandboxJobStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class SandboxOwnershipClaim {

    private final UUID jobId;
    private final UUID workerNodeId;
    private final UUID leaseToken;
    private final Long fencingVersion;
    private final Instant leaseExpiresAt;
    private final SandboxJobStatus status;

    public SandboxOwnershipClaim(UUID jobId, UUID workerNodeId, UUID leaseToken, Long fencingVersion, Instant leaseExpiresAt, SandboxJobStatus status) {
        this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
        this.workerNodeId = Objects.requireNonNull(workerNodeId, "workerNodeId must not be null");
        this.leaseToken = Objects.requireNonNull(leaseToken, "leaseToken must not be null");
        this.fencingVersion = Objects.requireNonNull(fencingVersion, "fencingVersion must not be null");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getWorkerNodeId() {
        return workerNodeId;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public SandboxJobStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SandboxOwnershipClaim claim = (SandboxOwnershipClaim) o;
        return Objects.equals(jobId, claim.jobId) &&
                Objects.equals(workerNodeId, claim.workerNodeId) &&
                Objects.equals(leaseToken, claim.leaseToken) &&
                Objects.equals(fencingVersion, claim.fencingVersion) &&
                Objects.equals(leaseExpiresAt, claim.leaseExpiresAt) &&
                status == claim.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, workerNodeId, leaseToken, fencingVersion, leaseExpiresAt, status);
    }

    @Override
    public String toString() {
        return "SandboxOwnershipClaim{" +
                "jobId=" + jobId +
                ", workerNodeId=" + workerNodeId +
                ", fencingVersion=" + fencingVersion +
                ", leaseExpiresAt=" + leaseExpiresAt +
                ", status=" + status +
                '}';
    }
}
