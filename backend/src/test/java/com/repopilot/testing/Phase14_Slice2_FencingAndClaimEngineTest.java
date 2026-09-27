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
import com.repopilot.testing.entity.*;
import com.repopilot.testing.exception.JobClaimException;
import com.repopilot.testing.exception.StaleWorkerFencingException;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase14_Slice2_FencingAndClaimEngineTest {

    @Autowired private SandboxFencingService fencingService;

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

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("slice142_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Slice 14.2 Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        project = new Project();
        project.setName("Slice 14.2 Project");
        project.setDescription("Project for Phase 14.2 fencing testing");
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
        task.setTitle("Slice 14.2 Task");
        task.setDescription("Task for Slice 14.2 testing");
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
        worker1.setNodeIdentifier("node-worker-01-" + UUID.randomUUID().toString().substring(0, 8));
        worker1.setSocketPath("/var/run/repopilot-node-1.sock");
        worker1.setStatus("HEALTHY");
        worker1 = sandboxWorkerNodeRepository.save(worker1);

        worker2 = new SandboxWorkerNode();
        worker2.setNodeIdentifier("node-worker-02-" + UUID.randomUUID().toString().substring(0, 8));
        worker2.setSocketPath("/var/run/repopilot-node-2.sock");
        worker2.setStatus("HEALTHY");
        worker2 = sandboxWorkerNodeRepository.save(worker2);
    }

    @AfterEach
    void tearDown() {
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
    @DisplayName("A. Two workers claim simultaneously -> exactly one success")
    void testA_twoWorkersConcurrentClaim() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_CONCURRENT_2");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Callable<Void> claimTask1 = () -> {
            latch.await();
            try {
                fencingService.claimJob(job.getId(), worker1.getId(), 60);
                successCount.incrementAndGet();
            } catch (JobClaimException e) {
                failureCount.incrementAndGet();
            }
            return null;
        };

        Callable<Void> claimTask2 = () -> {
            latch.await();
            try {
                fencingService.claimJob(job.getId(), worker2.getId(), 60);
                successCount.incrementAndGet();
            } catch (JobClaimException e) {
                failureCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(claimTask1);
        Future<Void> f2 = executor.submit(claimTask2);
        latch.countDown();

        f1.get();
        f2.get();
        executor.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(1, failureCount.get());

        SandboxJobExecution finalJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        assertEquals(2L, finalJob.getFencingVersion());
        assertEquals(SandboxJobStatus.CLAIMED, finalJob.getStatus());
    }

    @Test
    @DisplayName("B. Ten workers claim simultaneously -> exactly one success")
    void testB_tenWorkersConcurrentClaim() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_CONCURRENT_10");
        int numWorkers = 10;
        List<SandboxWorkerNode> workers = new ArrayList<>();
        for (int i = 0; i < numWorkers; i++) {
            SandboxWorkerNode w = new SandboxWorkerNode();
            w.setNodeIdentifier("node-ten-worker-" + i + "-" + UUID.randomUUID().toString().substring(0, 6));
            w.setSocketPath("/var/run/sock-" + i);
            workers.add(sandboxWorkerNodeRepository.save(w));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numWorkers);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<Void>> futures = new ArrayList<>();
        for (SandboxWorkerNode w : workers) {
            futures.add(executor.submit(() -> {
                latch.await();
                try {
                    fencingService.claimJob(job.getId(), w.getId(), 60);
                    successCount.incrementAndGet();
                } catch (JobClaimException e) {
                    failureCount.incrementAndGet();
                }
                return null;
            }));
        }

        latch.countDown();
        for (Future<Void> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertEquals(1, successCount.get());
        assertEquals(9, failureCount.get());

        SandboxJobExecution finalJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        assertEquals(2L, finalJob.getFencingVersion());
    }

    @Test
    @DisplayName("C-G. Worker A loses lease to B -> A's heartbeat, status, complete, fail, release are rejected")
    void testC_to_G_staleWorkerRejections() {
        SandboxJobExecution job = createPendingJob("CMD_STALE_MUTATIONS");
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 1);
        assertEquals(2L, claimA.getFencingVersion());

        // Simulate lease expiration by manually backdating lease_expires_at
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        // Worker B reclaims expired job
        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(3L, claimB.getFencingVersion());

        // D. Stale heartbeat by A rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.renewLease(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 60));

        // E. Stale completion by A rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.completeJob(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 0, "container-a"));

        // F. Stale failure by A rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.failJob(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 1));

        // G. Stale release by A rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.releaseJob(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion()));

        // Verify Worker B's state remains intact
        SandboxJobExecution finalJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        assertEquals(worker2.getId(), finalJob.getAssignedWorkerNode().getId());
        assertEquals(claimB.getLeaseToken(), finalJob.getLeaseToken());
        assertEquals(3L, finalJob.getFencingVersion());
        assertEquals(SandboxJobStatus.CLAIMED, finalJob.getStatus());
    }

    @Test
    @DisplayName("H-I. Correct owner heartbeat extends lease; expired lease heartbeat rejected")
    void testH_and_I_heartbeatSemantics() {
        SandboxJobExecution job = createPendingJob("CMD_HEARTBEAT");
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        // H. Valid owner heartbeat extends lease
        SandboxOwnershipClaim renewed = fencingService.renewLease(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 120);
        assertTrue(renewed.getLeaseExpiresAt().isAfter(claimA.getLeaseExpiresAt()));

        // Manually expire lease
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(5));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        // I. Heartbeat after lease expiration rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.renewLease(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), 60));
    }

    @Test
    @DisplayName("J-K. Active lease cannot be stolen; Expired lease can be reclaimed with higher fencingVersion")
    void testJ_and_K_leaseStealAndReclaim() {
        SandboxJobExecution job = createPendingJob("CMD_STEAL_RECLAIM");
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 300);

        // J. Active lease cannot be stolen
        assertThrows(JobClaimException.class, () ->
                fencingService.claimJob(job.getId(), worker2.getId(), 60));

        // Expire Worker A's lease manually
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        // K. Expired lease reclaimed by Worker B with fencingVersion incremented
        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(worker2.getId(), claimB.getWorkerNodeId());
        assertEquals(3L, claimB.getFencingVersion());
        assertNotEquals(claimA.getLeaseToken(), claimB.getLeaseToken());
    }

    @Test
    @DisplayName("L-P. Mismatched token, worker, or fencing version rejected")
    void testL_to_P_mismatchedIdentityRejections() {
        SandboxJobExecution job = createPendingJob("CMD_MISMATCH_CHECKS");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        UUID wrongToken = UUID.randomUUID();
        Long wrongFencingVersion = 999L;

        // L. Old/wrong lease token rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.updateStatus(job.getId(), worker1.getId(), wrongToken, claim.getFencingVersion(), SandboxJobStatus.RUNNING));

        // M. Same workerId but wrong token rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.completeJob(job.getId(), worker1.getId(), wrongToken, claim.getFencingVersion(), 0, "c1"));

        // N. Correct worker + correct token + wrong fencing version rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.failJob(job.getId(), worker1.getId(), claim.getLeaseToken(), wrongFencingVersion, 1));

        // O. Correct fencing version + wrong worker rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.updateStatus(job.getId(), worker2.getId(), claim.getLeaseToken(), claim.getFencingVersion(), SandboxJobStatus.RUNNING));

        // P. Correct fencing version + wrong lease token rejected
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.releaseJob(job.getId(), worker1.getId(), wrongToken, claim.getFencingVersion()));
    }

    @Test
    @DisplayName("S-T. Graceful release and fencingVersion monotonicity")
    void testS_and_T_releaseAndFencingMonotonicity() {
        SandboxJobExecution job = createPendingJob("CMD_RELEASE_MONOTONIC");
        SandboxOwnershipClaim claim1 = fencingService.claimJob(job.getId(), worker1.getId(), 60);
        assertEquals(2L, claim1.getFencingVersion());

        // Worker 1 releases job
        fencingService.releaseJob(job.getId(), worker1.getId(), claim1.getLeaseToken(), claim1.getFencingVersion());

        // Repeated release by Worker 1 fails
        assertThrows(StaleWorkerFencingException.class, () ->
                fencingService.releaseJob(job.getId(), worker1.getId(), claim1.getLeaseToken(), claim1.getFencingVersion()));

        // Worker 2 claims released job -> fencingVersion increments to 3
        SandboxOwnershipClaim claim2 = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(3L, claim2.getFencingVersion());

        // Worker 2 releases job
        fencingService.releaseJob(job.getId(), worker2.getId(), claim2.getLeaseToken(), claim2.getFencingVersion());

        // Worker 1 claims job again -> fencingVersion increments to 4
        SandboxOwnershipClaim claim3 = fencingService.claimJob(job.getId(), worker1.getId(), 60);
        assertEquals(4L, claim3.getFencingVersion());
    }

    @Test
    @DisplayName("11. Hostile TOCTOU Concurrency Test using CountDownLatch Barriers")
    void test11_hostileTOCTOUConcurrencyTest() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_TOCTOU_TEST");
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 1);
        assertEquals(2L, claimA.getFencingVersion());

        CountDownLatch workerAPaused = new CountDownLatch(1);
        CountDownLatch workerBClaimed = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger workerAMutationResult = new AtomicInteger(0); // 0 = not run, 1 = success, 2 = rejected

        // Worker A thread simulate paused execution right before mutation
        Future<Void> workerAFuture = executor.submit(() -> {
            workerAPaused.countDown();
            workerBClaimed.await(); // Wait for Worker B to claim generation 3
            try {
                fencingService.updateStatus(job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(), SandboxJobStatus.RUNNING);
                workerAMutationResult.set(1);
            } catch (StaleWorkerFencingException e) {
                workerAMutationResult.set(2);
            }
            return null;
        });

        // Main thread waits for Worker A to pause, then expires lease and triggers Worker B claim
        workerAPaused.await();

        // Expire Worker A's lease in DB
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        // Worker B claims generation 3
        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(3L, claimB.getFencingVersion());

        // Resume Worker A
        workerBClaimed.countDown();
        workerAFuture.get();

        executor.shutdown();

        // Assert Worker A's delayed mutation was strictly rejected
        assertEquals(2, workerAMutationResult.get(), "Worker A's delayed mutation MUST be rejected with StaleWorkerFencingException");

        // Assert Worker B's state remains intact with fencingVersion = 3
        SandboxJobExecution finalJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        assertEquals(worker2.getId(), finalJob.getAssignedWorkerNode().getId());
        assertEquals(claimB.getLeaseToken(), finalJob.getLeaseToken());
        assertEquals(3L, finalJob.getFencingVersion());
        assertEquals(SandboxJobStatus.CLAIMED, finalJob.getStatus());
    }
}
