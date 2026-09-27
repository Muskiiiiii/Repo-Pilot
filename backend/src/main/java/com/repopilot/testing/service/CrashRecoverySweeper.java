package com.repopilot.testing.service;

import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.entity.RepairIteration;
import com.repopilot.testing.entity.RepairIterationStatus;
import com.repopilot.testing.repository.RepairIterationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class CrashRecoverySweeper {

    private static final Logger logger = LoggerFactory.getLogger(CrashRecoverySweeper.class);

    private final TaskExecutionRepository taskExecutionRepository;
    private final RepairIterationRepository repairIterationRepository;
    private final DurableOrchestratorOwnershipService ownershipService;
    private final DockerSandboxExecutorService dockerSandboxExecutorService;
    private final WorkspaceManagerService workspaceManagerService;
    private final MultiIterationRepairOrchestrationService orchestrationService;

    @Autowired
    public CrashRecoverySweeper(TaskExecutionRepository taskExecutionRepository,
                                RepairIterationRepository repairIterationRepository,
                                DurableOrchestratorOwnershipService ownershipService,
                                DockerSandboxExecutorService dockerSandboxExecutorService,
                                WorkspaceManagerService workspaceManagerService,
                                MultiIterationRepairOrchestrationService orchestrationService) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.repairIterationRepository = repairIterationRepository;
        this.ownershipService = ownershipService != null ? ownershipService : new DurableOrchestratorOwnershipServiceImpl(null);
        this.dockerSandboxExecutorService = dockerSandboxExecutorService;
        this.workspaceManagerService = workspaceManagerService;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(fixedDelay = 60000)
    public void sweepStaleExecutions() {
        if (dockerSandboxExecutorService != null) {
            try {
                dockerSandboxExecutorService.cleanupOrphanContainers();
            } catch (Exception e) {
                logger.warn("Scheduled orphan container cleanup error: {}", e.getMessage());
            }
        }

        // Query executions stuck in RUNNING or PREPARING for > 120 seconds
        Instant staleThreshold = Instant.now().minusSeconds(120);
        List<TaskExecution> staleExecutions = taskExecutionRepository.findStaleExecutions(staleThreshold);
        if (staleExecutions == null || staleExecutions.isEmpty()) {
            return;
        }

        logger.info("Found {} potentially stale task executions for crash recovery", staleExecutions.size());

        for (TaskExecution exec : staleExecutions) {
            recoverStaleExecution(exec.getId());
        }
    }

    public boolean recoverStaleExecution(UUID executionId) {
        if (executionId == null) {
            return false;
        }

        // 1. Check if active ownership lease is currently held in Redis
        if (ownershipService.isOwned(executionId)) {
            logger.debug("Execution {} is currently owned by an active worker, skipping sweep", executionId);
            return false;
        }

        // 2. Try to acquire leadership
        String acquiredToken = ownershipService.acquireOwnership(executionId, Duration.ofSeconds(60));
        if (acquiredToken == null) {
            logger.debug("Could not acquire Redis lease for execution {}", executionId);
            return false;
        }

        try {
            // 3. Atomically increment PostgreSQL fencing epoch
            int claimed = taskExecutionRepository.claimLeadershipAndIncrementEpoch(executionId, acquiredToken);
            if (claimed == 0) {
                logger.info("Leadership claim failed (execution terminal or claimed by another node) for {}", executionId);
                return false;
            }

            // Reload fresh TaskExecution state
            TaskExecution execution = taskExecutionRepository.findById(executionId).orElse(null);
            if (execution == null) {
                return false;
            }

            Long currentEpoch = execution.getFencingEpoch() != null ? execution.getFencingEpoch() : 1L;
            Integer version = execution.getVersion() != null ? execution.getVersion() : 0;

            // 4. Handle Monotonic Cancellation
            if (Boolean.TRUE.equals(execution.getCancellationRequested())) {
                logger.info("Sweeper processing cancelled execution {}", executionId);
                // Container cleanup MUST precede workspace directory cleanup
                if (dockerSandboxExecutorService != null) {
                    dockerSandboxExecutorService.cleanupContainersByExecutionId(executionId);
                }
                if (workspaceManagerService != null) {
                    workspaceManagerService.cleanupWorkspace(executionId);
                }
                taskExecutionRepository.updateStatusWithFencingAndVersion(
                        executionId, ExecutionStatus.CANCELLED, Instant.now(), version, currentEpoch);
                return true;
            }

            // 5. Increment recovery attempts counter with atomic server-authoritative ceiling (< 5) & fencing
            int incremented = taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(
                    executionId, version, currentEpoch);
            if (incremented == 0) {
                logger.warn("Recovery attempt increment rejected for execution {} (ceiling reached, fencing conflict, or terminal status)", executionId);
                TaskExecution reloaded = taskExecutionRepository.findById(executionId).orElse(null);
                if (reloaded != null && reloaded.getRecoveryAttempts() != null && reloaded.getRecoveryAttempts() >= 5) {
                    if (dockerSandboxExecutorService != null) {
                        dockerSandboxExecutorService.cleanupContainersByExecutionId(executionId);
                    }
                    if (workspaceManagerService != null) {
                        workspaceManagerService.cleanupWorkspace(executionId);
                    }
                    taskExecutionRepository.updateStatusWithFencingAndVersion(
                            executionId, ExecutionStatus.FAILED, Instant.now(), reloaded.getVersion(), currentEpoch);
                }
                return false;
            }

            // Reload fresh TaskExecution version after successful recovery attempt increment
            execution = taskExecutionRepository.findById(executionId).orElse(execution);
            version = execution.getVersion();

            // 6. Inspect latest RepairIteration status for D1/D2 crash reconciliation
            List<RepairIteration> iterations = repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId);
            if (iterations != null && !iterations.isEmpty()) {
                RepairIteration latestIter = iterations.get(iterations.size() - 1);
                RepairIterationStatus iterStatus = latestIter.getStatus();

                logger.info("Sweeper recovering execution {} at iteration {} status {}", executionId, latestIter.getIterationNumber(), iterStatus);

                // Container cleanup MUST precede workspace directory cleanup
                if (dockerSandboxExecutorService != null) {
                    dockerSandboxExecutorService.cleanupContainersByExecutionId(executionId);
                }

                if (iterStatus == RepairIterationStatus.VALIDATING || iterStatus == RepairIterationStatus.CREATED || iterStatus == RepairIterationStatus.DIAGNOSING || iterStatus == RepairIterationStatus.REPAIR_GENERATING || iterStatus == RepairIterationStatus.VALIDATED || iterStatus == RepairIterationStatus.EXECUTING) {
                    if (workspaceManagerService != null) {
                        workspaceManagerService.cleanupWorkspace(executionId);
                    }
                    Instant now = Instant.now();
                    int iterUpdated = repairIterationRepository.updateStatusWithFencingAndVersion(
                            latestIter.getId(),
                            RepairIterationStatus.EXECUTION_FAILED,
                            now,
                            latestIter.getVersion() != null ? latestIter.getVersion() : 0,
                            iterStatus,
                            currentEpoch
                    );
                    if (iterUpdated == 0) {
                        logger.warn("RepairIteration fencing mutation rejected for iteration {} (stale epoch {})", latestIter.getId(), currentEpoch);
                        return false;
                    }
                    latestIter.setStatus(RepairIterationStatus.EXECUTION_FAILED);
                    latestIter.setCompletedAt(now);
                }
            } else {
                // Interrupted before first iteration was recorded
                if (dockerSandboxExecutorService != null) {
                    dockerSandboxExecutorService.cleanupContainersByExecutionId(executionId);
                }
                if (workspaceManagerService != null) {
                    workspaceManagerService.cleanupWorkspace(executionId);
                }
            }

            // 7. Resume or complete orchestration safely
            if (iterations != null && iterations.size() >= 3) {
                taskExecutionRepository.updateStatusWithFencingAndVersion(
                        executionId, ExecutionStatus.FAILED, Instant.now(), version, currentEpoch);
            } else if (orchestrationService != null && execution.getProject() != null && execution.getRepository() != null && execution.getTask() != null) {
                try {
                    orchestrationService.orchestrateSelfCorrection(
                            execution.getProject().getId(),
                            execution.getRepository().getId(),
                            execution.getTask().getId(),
                            executionId,
                            null
                    );
                } catch (Exception e) {
                    logger.warn("Sweeper orchestration retry for {} failed: {}", executionId, e.getMessage());
                    taskExecutionRepository.updateStatusWithFencingAndVersion(
                            executionId, ExecutionStatus.FAILED, Instant.now(), version, currentEpoch);
                }
            } else {
                taskExecutionRepository.updateStatusWithFencingAndVersion(
                        executionId, ExecutionStatus.FAILED, Instant.now(), version, currentEpoch);
            }
            return true;
        } finally {
            try {
                ownershipService.releaseOwnership(executionId, acquiredToken);
            } catch (Exception e) {
                logger.warn("Error releasing sweeper ownership for {}: {}", executionId, e.getMessage());
            }
        }
    }
}
