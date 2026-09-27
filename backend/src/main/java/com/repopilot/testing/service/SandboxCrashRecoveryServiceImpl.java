package com.repopilot.testing.service;

import com.repopilot.testing.daemon.runtime.SandboxDockerRuntimeService;
import com.repopilot.testing.entity.SandboxJobExecution;
import com.repopilot.testing.entity.SandboxJobStatus;
import com.repopilot.testing.entity.SandboxWorkerNode;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import com.repopilot.testing.repository.SandboxWorkerNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class SandboxCrashRecoveryServiceImpl implements SandboxCrashRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxCrashRecoveryServiceImpl.class);

    private final SandboxWorkerNodeRepository workerNodeRepository;
    private final SandboxJobExecutionRepository jobExecutionRepository;
    private final SandboxDockerRuntimeService dockerRuntimeService;

    @Autowired
    public SandboxCrashRecoveryServiceImpl(SandboxWorkerNodeRepository workerNodeRepository,
                                           SandboxJobExecutionRepository jobExecutionRepository,
                                           SandboxDockerRuntimeService dockerRuntimeService) {
        this.workerNodeRepository = workerNodeRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.dockerRuntimeService = dockerRuntimeService;
    }

    @Override
    @Transactional
    public int reconcileStaleWorkers(Duration maxHeartbeatAge) {
        Duration threshold = (maxHeartbeatAge != null && !maxHeartbeatAge.isNegative()) ? maxHeartbeatAge : Duration.ofSeconds(60);
        Instant cutoff = Instant.now().minus(threshold);

        List<SandboxWorkerNode> staleNodes = workerNodeRepository.findStaleWorkers(cutoff);
        if (staleNodes == null || staleNodes.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (SandboxWorkerNode node : staleNodes) {
            node.setStatus("DEAD");
            workerNodeRepository.save(node);
            count++;
            logger.info("Reconciled dead worker node {} (last heartbeat: {})", node.getNodeIdentifier(), node.getLastHeartbeatAt());
        }

        return count;
    }

    @Override
    @Transactional
    public int reconcileOrphanContainers() {
        List<Map<String, String>> containers = dockerRuntimeService.discoverRepoPilotContainers();
        if (containers == null || containers.isEmpty()) {
            return 0;
        }

        int cleaned = 0;
        for (Map<String, String> container : containers) {
            String name = container.get("name");
            String jobIdStr = container.get("job_id");
            String fvStr = container.get("fencing_version");

            if (name == null || name.isBlank() || jobIdStr == null) {
                continue;
            }

            try {
                UUID jobId = UUID.fromString(jobIdStr);
                Long containerFv = (fvStr != null) ? Long.parseLong(fvStr) : 0L;

                SandboxJobExecution job = jobExecutionRepository.findById(jobId).orElse(null);

                boolean shouldClean = false;
                if (job == null) {
                    shouldClean = true; // Job missing from DB
                } else if (job.getStatus() == SandboxJobStatus.COMPLETED ||
                           job.getStatus() == SandboxJobStatus.FAILED ||
                           job.getStatus() == SandboxJobStatus.CANCELLED) {
                    shouldClean = true; // DB status is terminal
                } else if (job.getFencingVersion() > containerFv) {
                    shouldClean = true; // Container belongs to older fencing generation
                }

                if (shouldClean) {
                    logger.info("Cleaning up orphaned container {} (job: {}, container fv: {})", name, jobId, containerFv);
                    dockerRuntimeService.terminateContainer(name);
                    cleaned++;
                }
            } catch (Exception e) {
                logger.warn("Error evaluating container {} during orphan sweep: {}", name, e.getMessage());
            }
        }

        return cleaned;
    }

    @Override
    @Transactional
    public int reconcileOrphanWorkspaces() {
        Path root = Path.of("/tmp", "repopilot-workspaces").toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            return 0;
        }

        int cleaned = 0;
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> subdirs = stream.filter(Files::isDirectory).toList();

            for (Path dir : subdirs) {
                String dirName = dir.getFileName().toString();
                if (!dirName.startsWith("exec_")) {
                    continue;
                }

                Path canonical = dir.toAbsolutePath().normalize();
                if (!canonical.startsWith(root)) {
                    throw new SecurityException("Workspace traversal detected outside allowed root: " + dir);
                }

                // Check directory age
                Instant lastMod = Files.getLastModifiedTime(dir).toInstant();
                if (lastMod.isBefore(Instant.now().minus(Duration.ofMinutes(15)))) {
                    logger.info("Cleaning up orphaned workspace directory {}", dirName);
                    dockerRuntimeService.cleanupWorkspace(dir);
                    cleaned++;
                }
            }
        } catch (IOException e) {
            logger.warn("Error sweeping orphan workspaces: {}", e.getMessage());
        }

        return cleaned;
    }

    @Override
    @Transactional
    public boolean reconcileJobExecution(UUID jobId) {
        if (jobId == null) return false;

        SandboxJobExecution job = jobExecutionRepository.findById(jobId).orElse(null);
        if (job == null) return false;

        Instant now = Instant.now();

        // 1. Lease expired check
        if ((job.getStatus() == SandboxJobStatus.CLAIMED || job.getStatus() == SandboxJobStatus.RUNNING)
                && job.getLeaseExpiresAt() != null && job.getLeaseExpiresAt().isBefore(now)) {
            job.setStatus(SandboxJobStatus.LEASE_EXPIRED);
            jobExecutionRepository.save(job);
            logger.info("Reconciled execution {}: marked LEASE_EXPIRED", jobId);
            return true;
        }

        // 2. Container missing check
        if (job.getStatus() == SandboxJobStatus.RUNNING && job.getContainerId() != null) {
            boolean active = dockerRuntimeService.inspectContainer(job.getContainerId());
            if (!active) {
                job.setStatus(SandboxJobStatus.FAILED);
                job.setExitCode(-1);
                job.setCompletedAt(now);
                jobExecutionRepository.save(job);
                logger.info("Reconciled execution {}: marked FAILED (container missing)", jobId);
                return true;
            }
        }

        return false;
    }
}
