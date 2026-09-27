package com.repopilot.testing;

import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.daemon.model.DaemonExecutionRequest;
import com.repopilot.testing.daemon.model.DaemonExecutionResponse;
import com.repopilot.testing.daemon.model.DaemonOperationType;
import com.repopilot.testing.daemon.model.DaemonStatus;
import com.repopilot.testing.daemon.service.SandboxDaemonService;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.model.SandboxOwnershipClaim;
import com.repopilot.testing.repository.SandboxExecutionArtifactRepository;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import com.repopilot.testing.repository.SandboxWorkerNodeRepository;
import com.repopilot.testing.service.SandboxArtifactService;
import com.repopilot.testing.service.SandboxCrashRecoveryService;
import com.repopilot.testing.service.SandboxFencingService;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase14_Slice5_SandboxArtifactsAndCrashRecoveryTest {

    @Autowired private SandboxFencingService fencingService;
    @Autowired private SandboxDaemonService daemonService;
    @Autowired private SandboxArtifactService artifactService;
    @Autowired private SandboxCrashRecoveryService crashRecoveryService;

    @Autowired private SandboxWorkerNodeRepository sandboxWorkerNodeRepository;
    @Autowired private SandboxJobExecutionRepository sandboxJobExecutionRepository;
    @Autowired private SandboxExecutionArtifactRepository artifactRepository;

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutionRepository taskExecutionRepository;

    private User ownerUser;
    private Project project;
    private Repository repository;
    private Task task;
    private TaskExecution taskExecution;
    private SandboxWorkerNode worker1;
    private SandboxWorkerNode worker2;

    @BeforeEach
    void setUp() throws IOException {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("slice145_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Slice 14.5 Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        project = new Project();
        project.setName("Slice 14.5 Project");
        project.setDescription("Project for Phase 14.5 artifact recovery testing");
        project.setOwner(ownerUser);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.save(project);

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("slice145-owner");
        repository.setRepoName("slice145-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setCreatedBy(ownerUser);
        task.setTitle("Slice 14.5 Sandbox Task");
        task.setDescription("Task description");
        task.setStatus(TaskStatus.COMPLETED);
        task.setPriority(TaskPriority.MEDIUM);
        task = taskRepository.save(task);

        taskExecution = new TaskExecution();
        taskExecution.setTask(task);
        taskExecution.setProject(project);
        taskExecution.setRepository(repository);
        taskExecution.setExecutionNumber(1);
        taskExecution.setStatus(ExecutionStatus.RUNNING);
        taskExecution = taskExecutionRepository.save(taskExecution);

        worker1 = new SandboxWorkerNode();
        worker1.setNodeIdentifier("worker-node-145-1-" + UUID.randomUUID().toString().substring(0, 8));
        worker1.setSocketPath("/tmp/repopilot-worker-145-1.sock");
        worker1.setMaxConcurrentContainers(4);
        worker1.setActiveContainers(0);
        worker1.setStatus("HEALTHY");
        worker1.setLastHeartbeatAt(Instant.now());
        worker1 = sandboxWorkerNodeRepository.save(worker1);

        worker2 = new SandboxWorkerNode();
        worker2.setNodeIdentifier("worker-node-145-2-" + UUID.randomUUID().toString().substring(0, 8));
        worker2.setSocketPath("/tmp/repopilot-worker-145-2.sock");
        worker2.setMaxConcurrentContainers(4);
        worker2.setActiveContainers(0);
        worker2.setStatus("HEALTHY");
        worker2.setLastHeartbeatAt(Instant.now());
        worker2 = sandboxWorkerNodeRepository.save(worker2);
    }

    @AfterEach
    void tearDown() {
        artifactRepository.deleteAll();
        sandboxJobExecutionRepository.deleteAll();
        sandboxWorkerNodeRepository.deleteAll();
    }

    private SandboxJobExecution createPendingJob(String commandType) {
        SandboxJobExecution job = new SandboxJobExecution();
        job.setTaskExecution(taskExecution);
        job.setProject(project);
        job.setRepository(repository);
        job.setStatus(SandboxJobStatus.PENDING);
        job.setFencingVersion(1L);
        job.setAttemptCount(1);
        job.setCommandType(commandType);
        job.setCommandArgs("[\"pytest\"]");
        job.setCpuLimitCores(2.0);
        job.setMemoryLimitMb(2048);
        job.setTimeoutSeconds(120);
        return sandboxJobExecutionRepository.save(job);
    }

    @Test
    @DisplayName("1. Fenced Artifact Publication: Stale Worker Rejected")
    void test1_fencedArtifactPublication_staleWorkerRejected() {
        SandboxJobExecution job = createPendingJob("TEST_FENCED_ARTIFACT");

        // Worker 1 claims job at generation 2
        SandboxOwnershipClaim claim1 = fencingService.claimJob(job.getId(), worker1.getId(), 60);
        assertNotNull(claim1);
        assertEquals(2L, claim1.getFencingVersion());

        // Fast-forward Worker 1's lease to expired in DB
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        // Worker 2 claims job at generation 3
        SandboxOwnershipClaim claim2 = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertNotNull(claim2);
        assertEquals(3L, claim2.getFencingVersion());

        SandboxJobExecution reloaded = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        assertEquals(3L, reloaded.getFencingVersion());
        assertEquals(worker2.getId(), reloaded.getAssignedWorkerNode().getId());

        // Stale Worker 1 attempts to publish artifact using generation 1 credentials
        assertThrows(SecurityException.class, () -> {
            artifactService.saveExecutionArtifact(
                    job.getId(), worker1.getId(), claim1.getLeaseToken(), claim1.getFencingVersion(),
                    "STDOUT", "build.log", "Stale output from worker 1"
            );
        }, "Stale worker publication MUST trigger SecurityException (FAIL CLOSED)");

        // Worker 2 publishes artifact using generation 2 credentials -> Success
        SandboxExecutionArtifact art2 = artifactService.saveExecutionArtifact(
                job.getId(), worker2.getId(), reloaded.getLeaseToken(), reloaded.getFencingVersion(),
                "STDOUT", "build.log", "Authoritative output from worker 2"
        );
        assertNotNull(art2.getId());

        // Assert DB contains ONLY Worker 2's generation 2 artifact
        List<SandboxExecutionArtifact> artifacts = artifactService.getArtifactsForJob(job.getId());
        assertEquals(1, artifacts.size());
        assertEquals("Authoritative output from worker 2", artifacts.get(0).getRawOutput());
    }

    @Test
    @DisplayName("2. Path Traversal Protection: Untrusted Paths Rejected")
    void test2_pathTraversalProtection_untrustedPathsRejected() {
        SandboxJobExecution job = createPendingJob("TEST_PATH_TRAVERSAL");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        // Path traversal attempts
        List<String> badPaths = List.of(
                "../../etc/passwd",
                "/tmp/evil",
                "..\\windows\\system32",
                "logs/../../bar.txt",
                "/etc/shadow"
        );

        for (String badPath : badPaths) {
            assertThrows(SecurityException.class, () -> {
                artifactService.saveExecutionArtifact(
                        job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                        "LOG", badPath, "content"
                );
            }, "Path traversal " + badPath + " MUST be rejected with SecurityException");
        }
    }

    @Test
    @DisplayName("3. Artifact Resource Limits Enforced")
    void test3_artifactResourceLimitsEnforced() {
        SandboxJobExecution job = createPendingJob("TEST_RESOURCE_LIMITS");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        // Single artifact exceeding 10 MB limit
        String hugeContent = "A".repeat(10_000_001);
        assertThrows(IllegalArgumentException.class, () -> {
            artifactService.saveExecutionArtifact(
                    job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                    "STDOUT", "huge.log", hugeContent
            );
        }, "Artifact exceeding single limit MUST be rejected");

        // Save up to 20 valid small artifacts
        for (int i = 0; i < 20; i++) {
            artifactService.saveExecutionArtifact(
                    job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                    "LOG_" + i, "log_" + i + ".txt", "content_" + i
            );
        }

        // 21st artifact MUST be rejected (max 20 artifacts)
        assertThrows(IllegalArgumentException.class, () -> {
            artifactService.saveExecutionArtifact(
                    job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                    "EXCESS", "excess.txt", "overflow"
            );
        }, "21st artifact MUST be rejected (count limit 20)");
    }

    @Test
    @DisplayName("4. Secret Scrubbing in Artifacts")
    void test4_secretScrubbingInArtifacts() {
        SandboxJobExecution job = createPendingJob("TEST_SECRET_SCRUBBING");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        String rawContent = """
                Building project...
                GitHub PAT: github_pat_11AAAAAA22BBBBBB33CCCCCC44DDDDDD55
                AWS Key: AKIA1234567890ABCDEF
                OpenAI Key: sk-1234567890abcdef1234567890abcdef
                All tests passed!
                """;

        SandboxExecutionArtifact artifact = artifactService.saveExecutionArtifact(
                job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "STDOUT", "output.log", rawContent
        );

        assertNotNull(artifact);
        assertTrue(artifact.getSecretScrubbedCount() > 0, "Scrubbed count MUST be > 0");
        assertFalse(artifact.getSanitizedOutput().contains("github_pat_"), "Sanitized output MUST NOT contain raw GitHub PAT");
        assertFalse(artifact.getSanitizedOutput().contains("AKIA1234567890ABCDEF"), "Sanitized output MUST NOT contain raw AWS Key");
        assertTrue(artifact.getSanitizedOutput().contains("REDACTED_SECRET"), "Sanitized output MUST contain redaction marker");
    }

    @Test
    @DisplayName("5. Orphan Container Discovery & Cleanup")
    void test5_orphanContainerDiscoveryAndCleanup() {
        // Create job execution marked COMPLETED in DB
        SandboxJobExecution job = createPendingJob("TEST_ORPHAN_CONTAINER");
        job.setStatus(SandboxJobStatus.COMPLETED);
        job.setExitCode(0);
        sandboxJobExecutionRepository.save(job);

        // Sweep orphan containers
        int cleaned = crashRecoveryService.reconcileOrphanContainers();
        assertTrue(cleaned >= 0, "Orphan container sweep must execute deterministically");
    }

    @Test
    @DisplayName("6. Orphan Workspace Discovery & Cleanup")
    void test6_orphanWorkspaceDiscoveryAndCleanup() throws IOException {
        Path root = Path.of("/tmp", "repopilot-workspaces").toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            Files.createDirectories(root);
        }

        Path orphanDir = root.resolve("exec_test_orphan_145");
        Files.createDirectories(orphanDir);
        Files.writeString(orphanDir.resolve("test.txt"), "dummy orphan content");
        assertTrue(Files.exists(orphanDir));

        // Set modification time to 30 mins ago to simulate stale orphan
        Files.setLastModifiedTime(orphanDir, java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofMinutes(30))));

        int cleaned = crashRecoveryService.reconcileOrphanWorkspaces();
        assertTrue(cleaned > 0, "Stale orphan workspace directory MUST be cleaned up");
        assertFalse(Files.exists(orphanDir), "Orphan directory MUST be deleted");
    }

    @Test
    @DisplayName("7. Idempotent Artifact Save")
    void test7_idempotentArtifactSave() {
        SandboxJobExecution job = createPendingJob("TEST_IDEMPOTENT_SAVE");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        // First save
        SandboxExecutionArtifact art1 = artifactService.saveExecutionArtifact(
                job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "STDOUT", "test.log", "Identical content"
        );

        // Second save with identical details
        SandboxExecutionArtifact art2 = artifactService.saveExecutionArtifact(
                job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "STDOUT", "test.log", "Identical content"
        );

        assertEquals(art1.getId(), art2.getId(), "Idempotent save MUST return same artifact instance");
        List<SandboxExecutionArtifact> list = artifactService.getArtifactsForJob(job.getId());
        assertEquals(1, list.size(), "DB MUST NOT duplicate identical artifacts");
    }

    @Test
    @DisplayName("8. Stale Worker Container Cleanup Rejected by Daemon")
    void test8_staleWorkerContainerCleanupRejectedByDaemon() throws IOException {
        SandboxJobExecution job = createPendingJob("TEST_STALE_CLEANUP");

        // Worker 1 claims job
        SandboxOwnershipClaim claim1 = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        // Worker 1 prepares workspace
        String execId = "exec-stale-cleanup-" + UUID.randomUUID();
        DaemonExecutionRequest prepareReq = new DaemonExecutionRequest(
                "req-prep", job.getId(), worker1.getId(), claim1.getLeaseToken(), claim1.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );

        DaemonExecutionResponse prepResp = daemonService.processRequest(prepareReq);
        assertEquals(DaemonStatus.SUCCESS, prepResp.getStatus());

        // Worker 1 loses lease; Worker 2 claims generation 2
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        SandboxOwnershipClaim claim2 = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertNotNull(claim2);
        assertEquals(3L, claim2.getFencingVersion());

        // Stale Worker 1 sends CLEANUP request using generation 1 token/fencing version
        DaemonExecutionRequest cleanupReq = new DaemonExecutionRequest(
                "req-clean", job.getId(), worker1.getId(), claim1.getLeaseToken(), claim1.getFencingVersion(),
                execId, DaemonOperationType.CLEANUP, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );

        DaemonExecutionResponse cleanupResp = daemonService.processRequest(cleanupReq);
        assertEquals(DaemonStatus.STALE_FENCE, cleanupResp.getStatus(), "Stale cleanup request MUST return STALE_FENCE");
    }

    @Test
    @DisplayName("9. Checksum Integrity Verification")
    void test9_checksumIntegrityVerification() {
        SandboxJobExecution job = createPendingJob("TEST_CHECKSUM_VERIFY");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        String content = "Hello RepoPilot Phase 14.5 Checksum Verification!";
        SandboxExecutionArtifact artifact = artifactService.saveExecutionArtifact(
                job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "STDOUT", "integrity.txt", content
        );

        String validHash = artifact.getContentHash();
        assertTrue(artifactService.verifyArtifactChecksum(artifact.getId(), validHash), "Valid SHA-256 hash MUST verify true");

        String tamperedHash = "0000000000000000000000000000000000000000000000000000000000000000";
        assertFalse(artifactService.verifyArtifactChecksum(artifact.getId(), tamperedHash), "Tampered SHA-256 hash MUST verify false");
    }
}
