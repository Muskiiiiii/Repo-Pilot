package com.repopilot.testing.service;

import java.time.Duration;
import java.util.UUID;

public interface SandboxCrashRecoveryService {

    /**
     * Reconciles stale worker nodes that haven't sent a heartbeat within threshold.
     */
    int reconcileStaleWorkers(Duration maxHeartbeatAge);

    /**
     * Discovers Docker containers with repopilot labels and cleans up orphaned/stale containers.
     */
    int reconcileOrphanContainers();

    /**
     * Discovers orphaned workspace directories and removes them safely after DB state check.
     */
    int reconcileOrphanWorkspaces();

    /**
     * Reconciles a single job execution state against container/worker state.
     */
    boolean reconcileJobExecution(UUID jobId);
}
