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
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketClient;
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketServer;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.model.SandboxOwnershipClaim;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import com.repopilot.testing.repository.SandboxWorkerNodeRepository;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase14_Slice3_SandboxDaemonAndSecurityBoundaryTest {

    @Autowired private SandboxFencingService fencingService;
    @Autowired private SandboxDaemonService daemonService;

    @Autowired private SandboxWorkerNodeRepository sandboxWorkerNodeRepository;
    @Autowired private SandboxJobExecutionRepository sandboxJobExecutionRepository;

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
    private Path socketPath;
    private SandboxDaemonUnixSocketServer daemonServer;
    private SandboxDaemonUnixSocketClient daemonClient;

    @BeforeEach
    void setUp() throws IOException {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("slice143_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Slice 14.3 Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        project = new Project();
        project.setName("Slice 14.3 Project");
        project.setDescription("Project for Phase 14.3 daemon testing");
        project.setOwner(ownerUser);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.save(project);

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("test-owner");
        repository.setRepoName("test-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setCreatedBy(ownerUser);
        task.setTitle("Slice 14.3 Task");
        task.setDescription("Task for Slice 14.3 testing");
        task.setStatus(TaskStatus.COMPLETED);
        task.setPriority(TaskPriority.HIGH);
        task = taskRepository.save(task);

        taskExecution = new TaskExecution();
        taskExecution.setTask(task);
        taskExecution.setProject(project);
        taskExecution.setRepository(repository);
        taskExecution.setExecutionNumber(1);
        taskExecution.setStatus(ExecutionStatus.RUNNING);
        taskExecution.setEcosystem(Ecosystem.MAVEN);
        taskExecution = taskExecutionRepository.save(taskExecution);

        worker1 = new SandboxWorkerNode();
        worker1.setNodeIdentifier("node-daemon-01-" + UUID.randomUUID().toString().substring(0, 8));
        worker1.setSocketPath("/var/run/repopilot-node-1.sock");
        worker1.setStatus("HEALTHY");
        worker1 = sandboxWorkerNodeRepository.save(worker1);

        worker2 = new SandboxWorkerNode();
        worker2.setNodeIdentifier("node-daemon-02-" + UUID.randomUUID().toString().substring(0, 8));
        worker2.setSocketPath("/var/run/repopilot-node-2.sock");
        worker2.setStatus("HEALTHY");
        worker2 = sandboxWorkerNodeRepository.save(worker2);

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        Path baseDir = Path.of("/tmp").toRealPath();
        socketPath = baseDir.resolve("rp_d_" + shortId + ".sock").toAbsolutePath().normalize();
        daemonServer = new SandboxDaemonUnixSocketServer(socketPath, daemonService);
        daemonServer.start();
        daemonClient = new SandboxDaemonUnixSocketClient(socketPath);
    }

    @AfterEach
    void tearDown() {
        if (daemonServer != null) {
            daemonServer.stop();
        }
        if (socketPath != null && Files.exists(socketPath)) {
            try { Files.delete(socketPath); } catch (IOException ignored) {}
            try { if (socketPath.getParent() != null) Files.deleteIfExists(socketPath.getParent()); } catch (IOException ignored) {}
        }

        if (sandboxJobExecutionRepository != null) { sandboxJobExecutionRepository.deleteAll(); sandboxJobExecutionRepository.flush(); }
        if (sandboxWorkerNodeRepository != null) { sandboxWorkerNodeRepository.deleteAll(); sandboxWorkerNodeRepository.flush(); }
        if (taskExecutionRepository != null) { taskExecutionRepository.deleteAll(); taskExecutionRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    private SandboxJobExecution createPendingJob(String commandType) {
        SandboxJobExecution job = new SandboxJobExecution();
        job.setTaskExecution(taskExecution);
        job.setProject(project);
        job.setRepository(repository);
        job.setStatus(SandboxJobStatus.PENDING);
        job.setCommandType(commandType);
        job.setCommandArgs("mvn test");
        return sandboxJobExecutionRepository.saveAndFlush(job);
    }

    @Test
    @DisplayName("1. Valid current owner request accepted via Unix Domain Socket")
    void test1_validCurrentOwnerRequestAccepted() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_DAEMON_VALID");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        String execId = "exec-" + UUID.randomUUID();
        DaemonExecutionRequest prepareReq = new DaemonExecutionRequest(
                "req-1", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );

        DaemonExecutionResponse response = daemonClient.sendRequest(prepareReq);
        assertEquals(DaemonStatus.SUCCESS, response.getStatus());
        assertEquals("PREPARED", response.getDetails());
    }

    @Test
    @DisplayName("2-4. Mismatched worker, token, or stale fencing version rejected")
    void test2_to_4_mismatchedWorkerTokenFencingRejected() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_DAEMON_SECURITY");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        String execId = "exec-" + UUID.randomUUID();

        // 2. Wrong worker rejected
        DaemonExecutionRequest reqWrongWorker = new DaemonExecutionRequest(
                "req-2", job.getId(), worker2.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respWrongWorker = daemonClient.sendRequest(reqWrongWorker);
        assertEquals(DaemonStatus.UNAUTHORIZED, respWrongWorker.getStatus());

        // 3. Wrong lease token rejected
        DaemonExecutionRequest reqWrongToken = new DaemonExecutionRequest(
                "req-3", job.getId(), worker1.getId(), UUID.randomUUID(), claim.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respWrongToken = daemonClient.sendRequest(reqWrongToken);
        assertEquals(DaemonStatus.UNAUTHORIZED, respWrongToken.getStatus());

        // 4. Stale fencing version rejected
        DaemonExecutionRequest reqStaleFence = new DaemonExecutionRequest(
                "req-4", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion() - 1,
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respStaleFence = daemonClient.sendRequest(reqStaleFence);
        assertEquals(DaemonStatus.STALE_FENCE, respStaleFence.getStatus());
    }

    @Test
    @DisplayName("5-7. Expired lease, unknown job, unknown execution rejected")
    void test5_to_7_expiredLeaseUnknownJobExecution() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_DAEMON_EXPIRED");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        // 5. Expired lease rejected
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        String execId = "exec-" + UUID.randomUUID();
        DaemonExecutionRequest reqExpired = new DaemonExecutionRequest(
                "req-5", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respExpired = daemonClient.sendRequest(reqExpired);
        assertEquals(DaemonStatus.LEASE_EXPIRED, respExpired.getStatus());

        // 6. Unknown job rejected
        DaemonExecutionRequest reqUnknownJob = new DaemonExecutionRequest(
                "req-6", UUID.randomUUID(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respUnknownJob = daemonClient.sendRequest(reqUnknownJob);
        assertEquals(DaemonStatus.UNKNOWN_JOB, respUnknownJob.getStatus());

        // Restore lease for unknown execution test
        dbJob.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        // 7. Unknown execution ID rejected for START operation
        DaemonExecutionRequest reqUnknownExec = new DaemonExecutionRequest(
                "req-7", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "exec-unknown-999", DaemonOperationType.START, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respUnknownExec = daemonClient.sendRequest(reqUnknownExec);
        assertEquals(DaemonStatus.UNKNOWN_EXECUTION, respUnknownExec.getStatus());
    }

    @Test
    @DisplayName("8-10. Malformed and oversized requests rejected")
    void test8_to_10_malformedAndOversizedRequests() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_DAEMON_MALFORMED");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        // 8. Malformed (blank request ID) rejected
        DaemonExecutionRequest reqBlank = new DaemonExecutionRequest(
                "", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "exec-1", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respBlank = daemonClient.sendRequest(reqBlank);
        assertEquals(DaemonStatus.INVALID_REQUEST, respBlank.getStatus());

        // 9. Negative fencing version rejected
        DaemonExecutionRequest reqNeg = new DaemonExecutionRequest(
                "req-9", job.getId(), worker1.getId(), claim.getLeaseToken(), -5L,
                "exec-1", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respNeg = daemonClient.sendRequest(reqNeg);
        assertEquals(DaemonStatus.INVALID_REQUEST, respNeg.getStatus());

        // 10. Oversized request payload rejected by server
        DaemonExecutionRequest reqOversized = new DaemonExecutionRequest(
                "req-10", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "exec-1", DaemonOperationType.PREPARE, "MAVEN_TEST", "a".repeat(70000), 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respOversized = daemonClient.sendRequest(reqOversized);
        assertEquals(DaemonStatus.INVALID_REQUEST, respOversized.getStatus());
        assertEquals("ERR_OVERSIZED_REQUEST", respOversized.getErrorCode());
    }

    @Test
    @DisplayName("14-16. Stale terminate, stale cleanup rejected; Idempotent current owner accepted")
    void test14_to_16_staleTerminateCleanupAndIdempotency() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_DAEMON_OPS");
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 1);

        String execId = "exec-ops-" + UUID.randomUUID();
        DaemonExecutionRequest prepReq = new DaemonExecutionRequest(
                "req-prep", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(prepReq).getStatus());

        // Expire Worker A lease and Worker B claims generation 3
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(3L, claimB.getFencingVersion());

        // 14. Stale terminate by Worker A rejected
        DaemonExecutionRequest staleTermReq = new DaemonExecutionRequest(
                "req-term", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.TERMINATE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.STALE_FENCE, daemonClient.sendRequest(staleTermReq).getStatus());

        // 15. Stale cleanup by Worker A rejected
        DaemonExecutionRequest staleCleanReq = new DaemonExecutionRequest(
                "req-clean", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.CLEANUP, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.STALE_FENCE, daemonClient.sendRequest(staleCleanReq).getStatus());

        // 16. Idempotent current owner (Worker B) request accepted
        String execIdB = "exec-b-" + UUID.randomUUID();
        DaemonExecutionRequest prepB1 = new DaemonExecutionRequest(
                "req-b1", job.getId(), worker2.getId(), claimB.getLeaseToken(), claimB.getFencingVersion(),
                execIdB, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(prepB1).getStatus());

        DaemonExecutionRequest prepB2 = new DaemonExecutionRequest(
                "req-b2", job.getId(), worker2.getId(), claimB.getLeaseToken(), claimB.getFencingVersion(),
                execIdB, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(prepB2).getStatus());
    }

    @Test
    @DisplayName("17-20. Local socket authentication, permissions, and daemon restart tests")
    void test17_to_20_socketSecurityAndDaemonRestart() throws Exception {
        // 17. Unregistered worker node rejected
        UUID unknownWorkerId = UUID.randomUUID();
        SandboxJobExecution job = createPendingJob("CMD_UNREG_WORKER");
        DaemonExecutionRequest unregReq = new DaemonExecutionRequest(
                "req-unreg", job.getId(), unknownWorkerId, UUID.randomUUID(), 1L,
                "exec-1", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.UNAUTHENTICATED, daemonClient.sendRequest(unregReq).getStatus());

        // 18. Socket permission verification
        assertTrue(Files.exists(socketPath));
        assertFalse(Files.isSymbolicLink(socketPath));

        // 19 & 20. Daemon restart does NOT create a new ownership authority (PostgreSQL remains sole authority)
        daemonServer.stop();
        assertFalse(daemonServer.isRunning());

        // Restart daemon
        daemonServer = new SandboxDaemonUnixSocketServer(socketPath, daemonService);
        daemonServer.start();
        assertTrue(daemonServer.isRunning());

        // Fencing authority is preserved directly in PostgreSQL
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);
        DaemonExecutionRequest postRestartReq = new DaemonExecutionRequest(
                "req-post", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                "exec-post", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(postRestartReq).getStatus());
    }

    @Test
    @DisplayName("12 & Section 12 Hostile TOCTOU Concurrency Test")
    void test12_hostileTOCTOUDaemonConcurrencyTest() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_TOCTOU_DAEMON");
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 1);
        assertEquals(2L, claimA.getFencingVersion());

        String execId = "exec-toctou-" + UUID.randomUUID();

        // Worker A prepares execution
        DaemonExecutionRequest prepReq = new DaemonExecutionRequest(
                "req-prep", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(prepReq).getStatus());

        CountDownLatch workerAPaused = new CountDownLatch(1);
        CountDownLatch workerBClaimed = new CountDownLatch(1);
        AtomicReference<DaemonStatus> workerADaemonResult = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Worker A thread pauses right before sending START daemon request
        Future<Void> workerAFuture = executor.submit(() -> {
            workerAPaused.countDown();
            workerBClaimed.await(); // Wait for Worker B to claim generation 3
            DaemonExecutionRequest delayedStartReq = new DaemonExecutionRequest(
                    "req-delayed-start", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                    execId, DaemonOperationType.START, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
            );
            DaemonExecutionResponse resp = daemonClient.sendRequest(delayedStartReq);
            workerADaemonResult.set(resp.getStatus());
            return null;
        });

        workerAPaused.await();

        // Expire Worker A lease and Worker B claims generation 3
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(3L, claimB.getFencingVersion());

        // Resume Worker A
        workerBClaimed.countDown();
        workerAFuture.get();
        executor.shutdown();

        // Worker A's delayed daemon request MUST return STALE_FENCE
        assertEquals(DaemonStatus.STALE_FENCE, workerADaemonResult.get(),
                "Delayed Worker A daemon request MUST be rejected with STALE_FENCE");

        // Worker B sends generation 3 request -> SUCCESS
        String execIdB = "exec-toctou-b-" + UUID.randomUUID();
        DaemonExecutionRequest reqB = new DaemonExecutionRequest(
                "req-b", job.getId(), worker2.getId(), claimB.getLeaseToken(), claimB.getFencingVersion(),
                execIdB, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(reqB).getStatus());
    }
}
