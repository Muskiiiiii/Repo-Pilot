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
import com.repopilot.testing.daemon.model.*;
import com.repopilot.testing.daemon.service.SandboxDaemonService;
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketClient;
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketServer;
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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase14_Slice6_FinalEndToEndHostileSecurityAndRecoveryGateTest {

    @Autowired private SandboxWorkerNodeRepository workerNodeRepository;
    @Autowired private SandboxJobExecutionRepository jobExecutionRepository;
    @Autowired private SandboxExecutionArtifactRepository artifactRepository;
    @Autowired private SandboxFencingService fencingService;
    @Autowired private SandboxArtifactService artifactService;
    @Autowired private SandboxCrashRecoveryService crashRecoveryService;
    @Autowired private SandboxDaemonService daemonService;

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutionRepository taskExecutionRepository;

    private User testUser;
    private Project testProject;
    private Repository testRepository;
    private Task testTask;
    private TaskExecution taskExecution;
    private SandboxWorkerNode worker1;
    private SandboxWorkerNode worker2;

    private Path socketPath;
    private SandboxDaemonUnixSocketServer daemonServer;
    private SandboxDaemonUnixSocketClient daemonClient;

    @BeforeEach
    void setUp() throws IOException {
        tearDown();

        testUser = new User();
        testUser.setEmail("slice146_" + UUID.randomUUID() + "@example.com");
        testUser.setPasswordHash("hash");
        testUser.setName("Slice 14.6 User");
        testUser.setRole(UserRole.USER);
        testUser = userRepository.save(testUser);

        testProject = new Project();
        testProject.setName("Slice 14.6 Project");
        testProject.setOwner(testUser);
        testProject.setStatus(ProjectStatus.ACTIVE);
        testProject = projectRepository.save(testProject);

        testRepository = new Repository();
        testRepository.setProject(testProject);
        testRepository.setOwnerName("test-owner");
        testRepository.setRepoName("test-repo-" + UUID.randomUUID().toString().substring(0, 8));
        testRepository.setDefaultBranch("main");
        testRepository.setVisibility(RepositoryVisibility.PRIVATE);
        testRepository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        testRepository = repositoryRepository.save(testRepository);

        testTask = new Task();
        testTask.setProject(testProject);
        testTask.setRepository(testRepository);
        testTask.setCreatedBy(testUser);
        testTask.setTitle("Slice 14.6 Task");
        testTask.setStatus(TaskStatus.COMPLETED);
        testTask.setPriority(TaskPriority.HIGH);
        testTask = taskRepository.save(testTask);

        taskExecution = new TaskExecution();
        taskExecution.setTask(testTask);
        taskExecution.setProject(testProject);
        taskExecution.setRepository(testRepository);
        taskExecution.setExecutionNumber(1);
        taskExecution.setStatus(ExecutionStatus.RUNNING);
        taskExecution.setEcosystem(Ecosystem.MAVEN);
        taskExecution = taskExecutionRepository.save(taskExecution);

        worker1 = new SandboxWorkerNode();
        worker1.setNodeIdentifier("worker-146-a-" + UUID.randomUUID().toString().substring(0, 6));
        worker1.setSocketPath("/var/run/worker1.sock");
        worker1.setMaxConcurrentContainers(4);
        worker1.setActiveContainers(0);
        worker1.setStatus("HEALTHY");
        worker1.setLastHeartbeatAt(Instant.now());
        worker1 = workerNodeRepository.save(worker1);

        worker2 = new SandboxWorkerNode();
        worker2.setNodeIdentifier("worker-146-b-" + UUID.randomUUID().toString().substring(0, 6));
        worker2.setSocketPath("/var/run/worker2.sock");
        worker2.setMaxConcurrentContainers(4);
        worker2.setActiveContainers(0);
        worker2.setStatus("HEALTHY");
        worker2.setLastHeartbeatAt(Instant.now());
        worker2 = workerNodeRepository.save(worker2);

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        Path baseDir = Path.of("/tmp").toRealPath();
        socketPath = baseDir.resolve("rp_gate146_" + shortId + ".sock").toAbsolutePath().normalize();

        daemonServer = new SandboxDaemonUnixSocketServer(socketPath, daemonService);
        daemonServer.start();
        daemonClient = new SandboxDaemonUnixSocketClient(socketPath);
    }

    @AfterEach
    void tearDown() {
        if (daemonServer != null) {
            daemonServer.stop();
        }
        if (artifactRepository != null) { artifactRepository.deleteAll(); artifactRepository.flush(); }
        if (jobExecutionRepository != null) { jobExecutionRepository.deleteAll(); jobExecutionRepository.flush(); }
        if (workerNodeRepository != null) { workerNodeRepository.deleteAll(); workerNodeRepository.flush(); }
        if (taskExecutionRepository != null) { taskExecutionRepository.deleteAll(); taskExecutionRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    private SandboxJobExecution createPendingJob(String commandType) {
        SandboxJobExecution job = new SandboxJobExecution();
        job.setTaskExecution(taskExecution);
        job.setProject(testProject);
        job.setRepository(testRepository);
        job.setCommandType(commandType);
        job.setCommandArgs("mvn test");
        job.setStatus(SandboxJobStatus.PENDING);
        job.setFencingVersion(0L);
        job.setCpuLimitCores(2.0);
        job.setMemoryLimitMb(2048);
        job.setTimeoutSeconds(120);
        job.setAttemptCount(0);
        return jobExecutionRepository.saveAndFlush(job);
    }

    @Test
    @DisplayName("1. Final Fencing Proof: All 10 worker operations by stale worker A (gen 1) rejected after B claims gen 2")
    void test1_finalFencingProofAllOperationsRejected() throws Exception {
        SandboxJobExecution job = createPendingJob("MAVEN_TEST");

        // Worker A claims generation 1
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 1);
        assertEquals(1L, claimA.getFencingVersion());

        // Expire Worker A's lease in DB
        SandboxJobExecution dbJob = jobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        jobExecutionRepository.saveAndFlush(dbJob);

        // Worker B claims generation 2
        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(2L, claimB.getFencingVersion());

        String execId = "exec-fencing-" + UUID.randomUUID();

        // 1. Stale Renew Lease rejected
        assertThrows(RuntimeException.class, () ->
                fencingService.renewLease(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 60)
        );

        // 2. Stale Status Update rejected
        assertThrows(RuntimeException.class, () ->
                fencingService.updateStatus(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), SandboxJobStatus.RUNNING)
        );

        // 3. Stale Complete rejected
        assertThrows(RuntimeException.class, () ->
                fencingService.completeJob(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 0, "c-1")
        );

        // 4. Stale Fail rejected
        assertThrows(RuntimeException.class, () ->
                fencingService.failJob(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 1)
        );

        // 5. Stale Release rejected
        assertThrows(RuntimeException.class, () ->
                fencingService.releaseJob(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion())
        );

        // 6. Stale Artifact Publication rejected
        assertThrows(RuntimeException.class, () ->
                artifactService.saveExecutionArtifact(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), "STDOUT", "stale.log", "stale stdout")
        );

        // 7. Daemon PREPARE with stale generation 1 rejected
        DaemonExecutionRequest prepStale = new DaemonExecutionRequest(
                "req-p1", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respPrep = daemonClient.sendRequest(prepStale);
        assertNotEquals(DaemonStatus.SUCCESS, respPrep.getStatus());

        // 8. Daemon INSPECT with stale generation 1 rejected
        DaemonExecutionRequest inspStale = new DaemonExecutionRequest(
                "req-p2", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.INSPECT, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respInsp = daemonClient.sendRequest(inspStale);
        assertNotEquals(DaemonStatus.SUCCESS, respInsp.getStatus());

        // 9. Daemon TERMINATE with stale generation 1 rejected
        DaemonExecutionRequest termStale = new DaemonExecutionRequest(
                "req-p3", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.TERMINATE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respTerm = daemonClient.sendRequest(termStale);
        assertNotEquals(DaemonStatus.SUCCESS, respTerm.getStatus());

        // 10. Daemon CLEANUP with stale generation 1 rejected
        DaemonExecutionRequest cleanStale = new DaemonExecutionRequest(
                "req-p4", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execId, DaemonOperationType.CLEANUP, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respClean = daemonClient.sendRequest(cleanStale);
        assertNotEquals(DaemonStatus.SUCCESS, respClean.getStatus());

        // Verify Worker B's generation 2 remains untouched
        SandboxJobExecution currentDb = jobExecutionRepository.findById(job.getId()).orElseThrow();
        assertEquals(2L, currentDb.getFencingVersion());
        assertEquals(worker2.getId(), currentDb.getAssignedWorkerNode().getId());
    }

    @Test
    @DisplayName("2. Worker-Dead Authority Test: Heartbeat renewal vs stale expiration")
    void test2_workerDeadAuthorityRaceCondition() {
        SandboxJobExecution job = createPendingJob("MAVEN_TEST");

        // Worker A claims generation 1
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 60);
        assertEquals(1L, claimA.getFencingVersion());

        // Worker A updates heartbeat while lease is still valid
        SandboxOwnershipClaim renewed = fencingService.renewLease(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 60);
        assertNotNull(renewed, "Worker A MUST successfully renew lease while still authoritative owner");

        // Stale worker reconciliation does NOT invalidate active worker 1
        int staleReconciled = crashRecoveryService.reconcileStaleWorkers(Duration.ofSeconds(120));
        assertEquals(0, staleReconciled, "Recovery MUST NOT reclaim worker with active heartbeat");

        // Now simulate true lease expiration
        SandboxJobExecution dbJob = jobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(300)); // 5 mins ago
        jobExecutionRepository.saveAndFlush(dbJob);

        // Worker B claims generation 2
        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(2L, claimB.getFencingVersion());

        // Stale Worker A can no longer renew lease
        assertThrows(RuntimeException.class, () ->
                fencingService.renewLease(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 60)
        );
    }

    @Test
    @DisplayName("3. IDOR / Cross-Execution Isolation Matrix: Mismatched parameter combinations rejected")
    void test3_idorCrossExecutionIsolationMatrix() throws Exception {
        SandboxJobExecution jobA = createPendingJob("MAVEN_TEST_A");
        SandboxJobExecution jobB = createPendingJob("MAVEN_TEST_B");

        SandboxOwnershipClaim claimA = fencingService.claimJob(jobA.getId(), worker1.getId(), 60);
        SandboxOwnershipClaim claimB = fencingService.claimJob(jobB.getId(), worker2.getId(), 60);

        // Mismatched job ID + claim token
        DaemonExecutionRequest reqMismatchJob = new DaemonExecutionRequest(
                "req-idor-1", jobA.getId(), worker1.getId(), claimB.getLeaseToken(), claimA.getFencingVersion(),
                "exec-idor-1", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respMismatchJob = daemonClient.sendRequest(reqMismatchJob);
        assertNotEquals(DaemonStatus.SUCCESS, respMismatchJob.getStatus());

        // Mismatched worker ID + claim token
        DaemonExecutionRequest reqMismatchWorker = new DaemonExecutionRequest(
                "req-idor-2", jobA.getId(), worker2.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                "exec-idor-2", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respMismatchWorker = daemonClient.sendRequest(reqMismatchWorker);
        assertNotEquals(DaemonStatus.SUCCESS, respMismatchWorker.getStatus());

        // Mismatched fencing version
        DaemonExecutionRequest reqMismatchFencing = new DaemonExecutionRequest(
                "req-idor-3", jobA.getId(), worker1.getId(), claimA.getLeaseToken(), 999L,
                "exec-idor-3", DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        DaemonExecutionResponse respMismatchFencing = daemonClient.sendRequest(reqMismatchFencing);
        assertNotEquals(DaemonStatus.SUCCESS, respMismatchFencing.getStatus());

        // Cross-job artifact publication attempt by Worker A to Job B
        assertThrows(RuntimeException.class, () ->
                artifactService.saveExecutionArtifact(jobB.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), "STDOUT", "stale.log", "stale")
        );
    }

    @Test
    @DisplayName("4. Database Integrity & Constraint Verification")
    void test4_databaseIntegrityAndConstraints() {
        // Unique node_identifier constraint
        SandboxWorkerNode dupNode = new SandboxWorkerNode();
        dupNode.setNodeIdentifier(worker1.getNodeIdentifier());
        dupNode.setSocketPath("/var/run/dup.sock");
        dupNode.setMaxConcurrentContainers(4);
        assertThrows(DataIntegrityViolationException.class, () -> workerNodeRepository.saveAndFlush(dupNode));

        // Unique task_execution_id + command_type constraint
        SandboxJobExecution dupJob = new SandboxJobExecution();
        dupJob.setTaskExecution(taskExecution);
        dupJob.setProject(testProject);
        dupJob.setRepository(testRepository);
        dupJob.setCommandType("MAVEN_TEST_DUP_UNIQUE");
        dupJob.setCommandArgs("mvn test");
        dupJob.setStatus(SandboxJobStatus.PENDING);
        jobExecutionRepository.saveAndFlush(dupJob);

        SandboxJobExecution dupJob2 = new SandboxJobExecution();
        dupJob2.setTaskExecution(taskExecution);
        dupJob2.setProject(testProject);
        dupJob2.setRepository(testRepository);
        dupJob2.setCommandType("MAVEN_TEST_DUP_UNIQUE");
        dupJob2.setCommandArgs("mvn test");
        dupJob2.setStatus(SandboxJobStatus.PENDING);
        assertThrows(DataIntegrityViolationException.class, () -> jobExecutionRepository.saveAndFlush(dupJob2));
    }

    @Test
    @DisplayName("5. Static Security Audit Sanity Check: No insecure Docker configurations in codebase")
    void test5_staticSecurityAuditSanity() throws IOException {
        Path sourceRoot = Path.of("/Users/yashsharma/Documents/RepoPilot_Project_new/backend/src/main/java/com/repopilot/testing");
        assertTrue(Files.exists(sourceRoot), "Phase 14 backend source directory must exist");

        List<String> prohibitedActiveInvocations = List.of(
                "--net=host", "--pid=host", "--ipc=host",
                "--cap-add=SYS_ADMIN", "--cap-add=NET_ADMIN", "--device=",
                ".addDevice(", ".withDevice("
        );

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            List<String> lines = Files.readAllLines(path);
                            for (String line : lines) {
                                if (line.contains("PROHIBITED") || line.contains("//") || line.contains("/*") || line.contains("* ")) {
                                    continue;
                                }
                                for (String pattern : prohibitedActiveInvocations) {
                                    assertFalse(line.contains(pattern),
                                            "Prohibited security enablement pattern '" + pattern + "' found in " + path.getFileName() + ": " + line);
                                }
                            }
                        } catch (IOException e) {
                            fail("Failed to read file: " + path);
                        }
                    });
        }
    }
}
