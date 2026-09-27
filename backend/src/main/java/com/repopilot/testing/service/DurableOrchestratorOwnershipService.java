package com.repopilot.testing.service;

import java.time.Duration;
import java.util.UUID;

public interface DurableOrchestratorOwnershipService {

    /**
     * Attempts to acquire durable orchestrator ownership for the given executionId.
     * Returns a unique server-generated ownerToken if successful, or null if ownership could not be acquired.
     */
    String acquireOwnership(UUID executionId, Duration ttl);

    /**
     * Renews ownership lease for an active owner matching the ownerToken.
     * Returns true if successfully renewed, false if stale owner token or ownership lost.
     */
    boolean renewOwnership(UUID executionId, String ownerToken, Duration ttl);

    /**
     * Verifies if the specified ownerToken currently holds authoritative ownership for executionId.
     * Returns true if valid owner, false if stale or non-owner.
     */
    boolean verifyOwnership(UUID executionId, String ownerToken);

    /**
     * Safely releases ownership for executionId using compare-and-release with ownerToken.
     * Returns true if released by matching owner, false if stale owner attempted release.
     */
    boolean releaseOwnership(UUID executionId, String ownerToken);

    /**
     * Starts periodic heartbeat task to renew ownership lease.
     */
    void startHeartbeat(UUID executionId, String ownerToken, Duration interval, Duration ttl);

    /**
     * Stops heartbeat task for executionId.
     */
    void stopHeartbeat(UUID executionId);

    /**
     * Checks if an active ownership lease key currently exists in Redis.
     */
    boolean isOwned(UUID executionId);
}
