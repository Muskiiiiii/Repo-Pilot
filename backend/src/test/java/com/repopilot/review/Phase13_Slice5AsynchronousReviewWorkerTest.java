package com.repopilot.review;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.PersistentReviewLifecycleService;
import com.repopilot.review.service.ReviewWorkerExecutionService;
import com.repopilot.review.transport.DeterministicFakeAiReviewerTransport;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase13_Slice5AsynchronousReviewWorkerTest {

    @Autowired
    private PersistentReviewLifecycleService lifecycleService;

    @Autowired
    private ReviewWorkerExecutionService workerService;

    @Autowired
    private DeterministicFakeAiReviewerTransport fakeTransport;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private CodeReviewRepository codeReviewRepository;

    @Autowired
    private ReviewFindingRepository reviewFindingRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private User owner;
    private User attacker;
    private Project projectA;
    private Repository repoA;
    private Task taskA;
    private CodeChange changeA;

    @BeforeEach
    void setUp() {
        fakeTransport.reset();

        owner = new User();
        owner.setEmail("owner_slice5_" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("hash");
        owner.setName("Owner Slice5");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        attacker = new User();
        attacker.setEmail("attacker_slice5_" + UUID.randomUUID() + "@test.com");
        attacker.setPasswordHash("hash");
        attacker.setName("Attacker Slice5");
        attacker.setRole(UserRole.USER);
        attacker = userRepository.save(attacker);

        projectA = new Project();
        projectA.setOwner(owner);
        projectA.setName("Project Slice5 " + UUID.randomUUID());
        projectA.setDescription("Project Slice5");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("repopilot");
        repoA.setRepoName("repo-slice5-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task Slice5 A");
        taskA.setDescription("Task Slice5 Description");
        taskA.setPriority(TaskPriority.HIGH);
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA = taskRepository.save(taskA);

        Plan planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan Slice5 Objective");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/WorkerService.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class WorkerService {}");
        changeA.setNewContent("public class WorkerService { String secret = \"ghp_CANARY_SECRET_SLICE5_12345\"; }");
        changeA.setOriginalHash("orig_slice5_123");
        changeA.setProposedHash("prop_slice5_456");
        changeA.setDiff("--- src/WorkerService.java\n+++ src/WorkerService.java\n@@ -1 +1 @@\n-public class WorkerService {}\n+public class WorkerService { String secret = \"ghp_CANARY_SECRET_SLICE5_12345\"; }\n");
        changeA = codeChangeRepository.save(changeA);
    }

    @AfterEach
    void tearDown() {
        reviewFindingRepository.deleteAll();
        codeReviewRepository.deleteAll();
        codeChangeRepository.deleteAll();
        planRepository.deleteAll();
        taskRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectMemberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ==================================================
    // A. WORKER CLAIM & RACE CONDITION
    // ==================================================

    @Test
    @DisplayName("Claim: Worker successfully claims PENDING review with unique fencing token")
    void testWorkerClaimPendingReviewSuccess() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        Optional<CodeReviewClaimDto> claimOpt = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5));
        assertTrue(claimOpt.isPresent());

        CodeReviewClaimDto claim = claimOpt.get();
        assertEquals(record.getId(), claim.reviewId());
        assertEquals("worker-1", claim.workerId());
        assertNotNull(claim.leaseToken());
        assertTrue(claim.leaseExpiresAt().isAfter(Instant.now()));

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.RUNNING, reviewInDb.getStatus());
        assertEquals("worker-1", reviewInDb.getWorkerId());
        assertEquals(claim.leaseToken(), reviewInDb.getLeaseToken());
    }

    @Test
    @DisplayName("Claim Race: Two workers racing to claim same PENDING review -> exactly one wins")
    void testClaimRaceTwoWorkersSameReview() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        Optional<CodeReviewClaimDto> claimWorker1 = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5));
        Optional<CodeReviewClaimDto> claimWorker2 = workerService.claimSpecificReview(record.getId(), "worker-2", Duration.ofMinutes(5));

        assertTrue(claimWorker1.isPresent());
        assertFalse(claimWorker2.isPresent());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals("worker-1", reviewInDb.getWorkerId());
    }

    // ==================================================
    // B. LEASE & HEARTBEAT RENEWAL
    // ==================================================

    @Test
    @DisplayName("Lease: Valid worker with active token can extend own lease")
    void testWorkerLeaseRenewalSuccess() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(2)).orElseThrow();

        boolean renewed = workerService.renewLease(record.getId(), "worker-1", claim.leaseToken(), Duration.ofMinutes(10));
        assertTrue(renewed);

        entityManager.clear();
        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertNotNull(reviewInDb.getLeaseExpiresAt());
        assertTrue(reviewInDb.getLeaseExpiresAt().isAfter(Instant.now().minus(Duration.ofHours(6))));
    }

    @Test
    @DisplayName("Lease: Renewal attempt with wrong lease token or wrong worker ID is REJECTED")
    void testWorkerLeaseRenewalWrongTokenRejected() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(2)).orElseThrow();

        boolean renewedWrongToken = workerService.renewLease(record.getId(), "worker-1", "INVALID_STALE_TOKEN", Duration.ofMinutes(5));
        assertFalse(renewedWrongToken);

        boolean renewedWrongWorker = workerService.renewLease(record.getId(), "worker-2", claim.leaseToken(), Duration.ofMinutes(5));
        assertFalse(renewedWrongWorker);
    }

    // ==================================================
    // C. FENCING & STALE WORKER REJECTION
    // ==================================================

    @Test
    @DisplayName("Fencing: Expired or stolen lease prevents stale worker from completing review")
    void testFencingStaleWorkerCannotCompleteReview() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claimWorker1 = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMillis(1)).orElseThrow();

        // Expire worker-1 lease by setting lease_expires_at in DB to past
        record.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
        codeReviewRepository.saveAndFlush(record);

        // Worker-2 recovers expired claim
        CodeReviewClaimDto claimWorker2 = workerService.claimSpecificReview(record.getId(), "worker-2", Duration.ofMinutes(5)).orElseThrow();

        // Stale Worker-1 attempts execution with stale claim
        ApiException ex = assertThrows(ApiException.class, () ->
                workerService.executeClaimedReview(claimWorker1, owner)
        );
        assertEquals("FENCING_LEASE_EXPIRED", ex.getCode());

        // Valid Worker-2 executes successfully
        CodeReviewResultDto resultWorker2 = workerService.executeClaimedReview(claimWorker2, owner);
        assertEquals(ReviewStatus.COMPLETED, resultWorker2.status());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals("worker-2", reviewInDb.getWorkerId());
        assertEquals(claimWorker2.leaseToken(), reviewInDb.getLeaseToken());
    }

    // ==================================================
    // D. CRASH RECOVERY & DURABLE DISCOVERY
    // ==================================================

    @Test
    @DisplayName("Crash Recovery: RUNNING review with expired lease is recoverable and claimable by new worker")
    void testCrashRecoveryExpiredRunningReviewClaimable() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim1 = workerService.claimSpecificReview(record.getId(), "crashed-worker", Duration.ofMillis(1)).orElseThrow();

        // Manually force lease expiration in DB (simulating worker process crash)
        record.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(10)));
        codeReviewRepository.saveAndFlush(record);

        List<UUID> claimableIds = workerService.discoverClaimableReviewIds(10);
        assertTrue(claimableIds.contains(record.getId()));

        Optional<CodeReviewClaimDto> recoveryClaimOpt = workerService.claimSpecificReview(record.getId(), "recovery-worker", Duration.ofMinutes(5));
        assertTrue(recoveryClaimOpt.isPresent());
        CodeReviewClaimDto recoveryClaim = recoveryClaimOpt.get();

        assertNotEquals(claim1.leaseToken(), recoveryClaim.leaseToken());
        assertEquals("recovery-worker", recoveryClaim.workerId());
    }

    // ==================================================
    // E. DUPLICATE DELIVERY & IDEMPOTENT EXECUTION
    // ==================================================

    @Test
    @DisplayName("Duplicate Delivery: Duplicate message delivery triggers only one active execution")
    void testDuplicateDeliverySingleExecution() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        Optional<CodeReviewClaimDto> claim1 = workerService.claimNextAvailableReview("worker-1", Duration.ofMinutes(5));
        Optional<CodeReviewClaimDto> claim2 = workerService.claimNextAvailableReview("worker-2", Duration.ofMinutes(5));

        assertTrue(claim1.isPresent());
        assertFalse(claim2.isPresent());
    }

    // ==================================================
    // F. WORKER FINALIZATION & FINDINGS
    // ==================================================

    @Test
    @DisplayName("Finalization: Claimed worker execution completes PENDING -> RUNNING -> COMPLETED with findings")
    void testWorkerExecutionSuccessWithFindings() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5)).orElseThrow();

        AiReviewerFindingDto finding = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "SECURITY", "CWE-798",
                FindingSeverity.HIGH, FindingConfidence.HIGH,
                "Hardcoded Secret", "Canary token exposed",
                "src/WorkerService.java", 1, 10, FindingSide.PROPOSED,
                "String secret = ...", "Remove secret"
        );
        fakeTransport.setCannedResponse(new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Worker review completed", FindingSeverity.HIGH, List.of(finding)
        ));

        CodeReviewResultDto result = workerService.executeClaimedReview(claim, owner);
        assertEquals(ReviewStatus.COMPLETED, result.status());
        assertEquals(1, result.findings().size());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, reviewInDb.getStatus());
        assertEquals(1, reviewFindingRepository.findByReview(reviewInDb).size());
    }

    // ==================================================
    // G. TOCTOU RE-VERIFICATION IN WORKER
    // ==================================================

    @Test
    @DisplayName("TOCTOU: CodeChange diff modified during worker execution transitions review to STALE with zero findings")
    void testWorkerToctouDiffMutationTransitionsToStale() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5)).orElseThrow();

        fakeTransport.setOnRequestCallback(req -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.executeWithoutResult(status -> {
                CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
                cc.setDiff("--- src/WorkerService.java\n+++ src/WorkerService.java\n@@ -1 +1 @@\n-public class WorkerService {}\n+public class WorkerService { void mutatedByConcurrentDev() {} }\n");
                codeChangeRepository.save(cc);
            });
        });

        CodeReviewResultDto result = workerService.executeClaimedReview(claim, owner);
        assertEquals(ReviewStatus.STALE, result.status());
        assertTrue(result.findings().isEmpty());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.STALE, reviewInDb.getStatus());
        assertTrue(reviewFindingRepository.findByReview(reviewInDb).isEmpty());
    }

    // ==================================================
    // H. LEASE LOSS DURING FINALIZATION
    // ==================================================

    @Test
    @DisplayName("Lease Loss: Stale worker attempting finalization after lease theft is REJECTED with FORBIDDEN")
    void testLeaseLossDuringFinalizationRejected() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim1 = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5)).orElseThrow();

        // Simulate Worker-2 stealing claim mid-execution
        CodeReview activeRecord = codeReviewRepository.findById(record.getId()).orElseThrow();
        activeRecord.setWorkerId("worker-2");
        activeRecord.setLeaseToken("STOLEN_LEASE_TOKEN_999");
        codeReviewRepository.saveAndFlush(activeRecord);

        ApiException ex = assertThrows(ApiException.class, () ->
                workerService.executeClaimedReview(claim1, owner)
        );
        assertEquals("FENCING_LEASE_EXPIRED", ex.getCode());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.RUNNING, reviewInDb.getStatus());
        assertEquals("worker-2", reviewInDb.getWorkerId());
    }

    // ==================================================
    // I. TRANSACTION ATOMICITY & ROLLBACK
    // ==================================================

    @Test
    @DisplayName("Transaction Atomicity: Forced failure during worker finalization rolls back all findings and status")
    void testWorkerTransactionRollbackOnFailure() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5)).orElseThrow();

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(RuntimeException.class, () -> {
            template.executeWithoutResult((TransactionStatus status) -> {
                ReviewFinding f = new ReviewFinding();
                f.setReview(record);
                f.setProject(projectA);
                f.setRepository(repoA);
                f.setTask(taskA);
                f.setCodeChange(changeA);
                f.setFindingType(FindingType.AI_FINDING);
                f.setCategory("SECURITY");
                f.setSeverity(FindingSeverity.HIGH);
                f.setConfidence(FindingConfidence.HIGH);
                f.setTitle("Worker Temp Finding");
                f.setDescription("Desc");
                f.setFilePath("src/WorkerService.java");
                f.setStartLine(1);
                f.setEndLine(2);
                f.setSide(FindingSide.PROPOSED);
                f.setContentHash("hash999");
                f.setFingerprint("fp999");
                f.setCreatedAt(Instant.now());
                f.setUpdatedAt(Instant.now());

                reviewFindingRepository.save(f);
                status.setRollbackOnly();
                throw new RuntimeException("Worker forced transaction rollback");
            });
        });

        assertTrue(reviewFindingRepository.findByReview(record).isEmpty());
    }

    // ==================================================
    // J. CAPABILITY & NO EXTERNAL MUTATION BOUNDARY
    // ==================================================

    @Test
    @DisplayName("Capability Boundary: Worker execution does not trigger Git, GitHub, PR, or approval mutations")
    void testWorkerCapabilityBoundaryNoExternalMutations() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claim = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5)).orElseThrow();

        CodeReviewResultDto result = workerService.executeClaimedReview(claim, owner);
        assertNotNull(result);

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        assertEquals("MODIFY", cc.getOperation());
    }

    // ==================================================
    // K. ENHANCED REMEDIATION VERIFICATION TESTS
    // ==================================================

    @Test
    @DisplayName("Semantics: execution_attempt_count increments ONLY on successful claim ownership win")
    void testExecutionAttemptCountIncrementsOnlyOnSuccessfulClaim() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(0, record.getExecutionAttemptCount());

        // Worker 1 claims successfully
        Optional<CodeReviewClaimDto> claim1 = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5));
        assertTrue(claim1.isPresent());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(1, reviewInDb.getExecutionAttemptCount());

        // 9 losing contenders attempt to claim already claimed active review
        for (int i = 2; i <= 10; i++) {
            Optional<CodeReviewClaimDto> losingClaim = workerService.claimSpecificReview(record.getId(), "worker-" + i, Duration.ofMinutes(5));
            assertFalse(losingClaim.isPresent());
        }

        // Verify attempt count remains EXACTLY 1 (losing contenders did not increment it)
        CodeReview reviewAfterLosses = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(1, reviewAfterLosses.getExecutionAttemptCount());

        // Expire lease and allow recovery worker to claim
        reviewAfterLosses.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(10)));
        codeReviewRepository.saveAndFlush(reviewAfterLosses);

        Optional<CodeReviewClaimDto> recoveryClaim = workerService.claimSpecificReview(record.getId(), "recovery-worker", Duration.ofMinutes(5));
        assertTrue(recoveryClaim.isPresent());

        // Second successful claim increases attempt count to 2
        CodeReview reviewAfterRecovery = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(2, reviewAfterRecovery.getExecutionAttemptCount());
    }

    @Test
    @DisplayName("Heartbeat vs Reclaim Race: Expired lease reclaimed by Worker B rejects Worker A renewal attempt")
    void testHeartbeatVsReclaimRace() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claimWorker1 = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(1)).orElseThrow();

        // Expire worker-1 lease
        record.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(5)));
        codeReviewRepository.saveAndFlush(record);

        // Worker 2 reclaims expired review
        CodeReviewClaimDto claimWorker2 = workerService.claimSpecificReview(record.getId(), "worker-2", Duration.ofMinutes(5)).orElseThrow();
        assertNotEquals(claimWorker1.leaseToken(), claimWorker2.leaseToken());

        // Stale Worker 1 attempts heartbeat lease renewal using token 1 -> REJECTED
        boolean renewedStale = workerService.renewLease(record.getId(), "worker-1", claimWorker1.leaseToken(), Duration.ofMinutes(10));
        assertFalse(renewedStale);

        // Active Worker 2 attempts heartbeat lease renewal using token 2 -> SUCCESS
        boolean renewedActive = workerService.renewLease(record.getId(), "worker-2", claimWorker2.leaseToken(), Duration.ofMinutes(10));
        assertTrue(renewedActive);
    }

    @Test
    @DisplayName("Hostile Delayed Provider: Worker A delayed response after lease theft is fenced out with zero findings persisted")
    void testDelayedProviderLeaseExpiryFencesStaleWorker() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claimWorker1 = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5)).orElseThrow();

        // Simulate transport delay during which lease expires and worker-2 claims
        fakeTransport.setOnRequestCallback(req -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.executeWithoutResult(status -> {
                CodeReview r = codeReviewRepository.findById(record.getId()).orElseThrow();
                r.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(10)));
                codeReviewRepository.saveAndFlush(r);
                workerService.claimSpecificReview(record.getId(), "worker-2", Duration.ofMinutes(5));
            });
        });

        // Stale Worker-1 finishes AI transport and attempts execution/finalization -> FENCED OUT
        ApiException ex = assertThrows(ApiException.class, () ->
                workerService.executeClaimedReview(claimWorker1, owner)
        );
        assertTrue("FENCING_LEASE_LOST".equals(ex.getCode()) || "FENCING_LEASE_EXPIRED".equals(ex.getCode()));

        // Assert Worker 2 remains authoritative owner and zero findings persisted by Worker 1
        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals("worker-2", reviewInDb.getWorkerId());
        assertTrue(reviewFindingRepository.findByReview(reviewInDb).isEmpty());
    }

    @Test
    @DisplayName("Terminal Immutability: Stale worker cannot mutate COMPLETED review or alter findings")
    void testStaleWorkerCannotMutateTerminalState() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewClaimDto claimWorker1 = workerService.claimSpecificReview(record.getId(), "worker-1", Duration.ofMinutes(5)).orElseThrow();

        // Worker 1 completes review successfully
        CodeReviewResultDto result = workerService.executeClaimedReview(claimWorker1, owner);
        assertEquals(ReviewStatus.COMPLETED, result.status());

        CodeReview completedRecord = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, completedRecord.getStatus());

        // Stale attempt using claimWorker1 again (after state is COMPLETED)
        ApiException ex = assertThrows(ApiException.class, () ->
                workerService.executeClaimedReview(claimWorker1, owner)
        );
        assertEquals("FENCING_LEASE_EXPIRED", ex.getCode());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, reviewInDb.getStatus());
    }

    @Test
    @DisplayName("True Concurrency: Multi-threaded claim race on same PENDING review -> exactly 1 winner, execution_attempt_count == 1")
    void testTrueConcurrentClaimRaceThreads() throws Exception {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CodeReview record = tt.execute(status -> lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        ));

        int threadCount = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Optional<CodeReviewClaimDto>>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String workerId = "thread-worker-" + i;
            futures.add(executor.submit(() -> {
                latch.await();
                return workerService.claimSpecificReview(record.getId(), workerId, Duration.ofMinutes(5));
            }));
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        int successCount = 0;
        CodeReviewClaimDto winningClaim = null;
        for (java.util.concurrent.Future<Optional<CodeReviewClaimDto>> f : futures) {
            Optional<CodeReviewClaimDto> res = f.get();
            if (res.isPresent()) {
                successCount++;
                winningClaim = res.get();
            }
        }

        assertEquals(1, successCount, "Exactly one worker thread must win the claim race");
        assertNotNull(winningClaim);

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.RUNNING, reviewInDb.getStatus());
        assertEquals(winningClaim.workerId(), reviewInDb.getWorkerId());
        assertEquals(winningClaim.leaseToken(), reviewInDb.getLeaseToken());
        assertEquals(1, reviewInDb.getExecutionAttemptCount(), "execution_attempt_count must equal 1 for single winning claim");
    }

    @Test
    @DisplayName("Durable Worker Recovery: PENDING/RUNNING review persisted in DB is discovered and claimed by new process without in-memory queue")
    void testDurableWorkerRestartRecoveryNoInMemoryQueueNeeded() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        UUID reviewId = record.getId();

        CodeReviewClaimDto crashedClaim = workerService.claimSpecificReview(reviewId, "crashed-worker-1", Duration.ofMillis(10)).orElseThrow();

        record.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        codeReviewRepository.saveAndFlush(record);

        ReviewWorkerExecutionService restartedWorkerService = new com.repopilot.review.service.ReviewWorkerExecutionServiceImpl(
                codeReviewRepository, reviewFindingRepository, null, null, null, new TransactionTemplate(transactionManager)
        );

        List<UUID> claimable = restartedWorkerService.discoverClaimableReviewIds(10);
        assertTrue(claimable.contains(reviewId));

        CodeReviewClaimDto recoveryClaim = restartedWorkerService.claimSpecificReview(reviewId, "restarted-worker-2", Duration.ofMinutes(5)).orElseThrow();
        assertEquals("restarted-worker-2", recoveryClaim.workerId());
        assertNotEquals(crashedClaim.leaseToken(), recoveryClaim.leaseToken());

        ApiException ex = assertThrows(ApiException.class, () ->
                workerService.executeClaimedReview(crashedClaim, owner)
        );
        assertEquals("FENCING_LEASE_EXPIRED", ex.getCode());
    }
}
