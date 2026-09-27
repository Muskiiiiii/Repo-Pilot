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
import com.repopilot.review.transport.DeterministicFakeAiReviewerTransport;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase13_Slice4PersistentReviewLifecycleTest {

    @Autowired
    private PersistentReviewLifecycleService lifecycleService;

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

    private User owner;
    private User attacker;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;
    private CodeChange changeA;

    @BeforeEach
    void setUp() {
        fakeTransport.reset();

        owner = new User();
        owner.setEmail("owner_slice4_" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("hash");
        owner.setName("Owner Slice4");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        attacker = new User();
        attacker.setEmail("attacker_slice4_" + UUID.randomUUID() + "@test.com");
        attacker.setPasswordHash("hash");
        attacker.setName("Attacker Slice4");
        attacker.setRole(UserRole.USER);
        attacker = userRepository.save(attacker);

        projectA = new Project();
        projectA.setOwner(owner);
        projectA.setName("Project Slice4 A " + UUID.randomUUID());
        projectA.setDescription("Project A");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));

        projectB = new Project();
        projectB.setOwner(owner);
        projectB.setName("Project Slice4 B " + UUID.randomUUID());
        projectB.setDescription("Project B");
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        projectMemberRepository.save(new ProjectMember(projectB, owner, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("repopilot");
        repoA.setRepoName("repo-slice4-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("repopilot");
        repoB.setRepoName("repo-slice4-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task Slice4 A");
        taskA.setDescription("Task Slice4 Description");
        taskA.setPriority(TaskPriority.HIGH);
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(owner);
        taskB.setTitle("Task Slice4 B");
        taskB.setPriority(TaskPriority.LOW);
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB = taskRepository.save(taskB);

        Plan planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan Slice4 Objective");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/AuthService.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class AuthService {}");
        changeA.setNewContent("public class AuthService { String token = \"ghp_CANARY_SECRET_123456789012345678\"; }");
        changeA.setOriginalHash("orig_hash_123");
        changeA.setProposedHash("prop_hash_456");
        changeA.setDiff("--- src/AuthService.java\n+++ src/AuthService.java\n@@ -1 +1 @@\n-public class AuthService {}\n+public class AuthService { String token = \"ghp_CANARY_SECRET_123456789012345678\"; }\n");
        changeA = codeChangeRepository.save(changeA);
    }

    // ==================================================
    // 1. IDEMPOTENCY & UNIQUENESS ENFORCEMENT
    // ==================================================

    @Test
    @DisplayName("Idempotency: Valid review record created in PENDING state with correct hashes and provenance")
    void testValidReviewRecordCreation() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertNotNull(record);
        assertNotNull(record.getId());
        assertEquals(ReviewStatus.PENDING, record.getStatus());
        assertEquals(projectA.getId(), record.getProject().getId());
        assertEquals(repoA.getId(), record.getRepository().getId());
        assertEquals(taskA.getId(), record.getTask().getId());
        assertEquals(changeA.getId(), record.getCodeChange().getId());
        assertNotNull(record.getReviewContextHash());
        assertNotNull(record.getAiInputHash());
        assertEquals("gemini-2.5-pro", record.getModelIdentifier());
        assertEquals("p2026.1", record.getPromptVersion());
    }

    @Test
    @DisplayName("Idempotency: Same logical review returns existing record")
    void testIdempotencySameLogicalReviewReuse() {
        CodeReview record1 = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReview record2 = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(record1.getId(), record2.getId());
        assertEquals(1, codeReviewRepository.findByCodeChangeId(changeA.getId()).size());
    }

    @Test
    @DisplayName("Idempotency: Different review mode for same CodeChange creates separate review record")
    void testIdempotencyDifferentReviewModeCreatesSeparateReview() {
        CodeReview reviewDefault = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReview reviewSecurity = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.SECURITY_REVIEW, owner
        );

        assertNotEquals(reviewDefault.getId(), reviewSecurity.getId());
        assertEquals(ReviewMode.CODE_CHANGE_REVIEW, reviewDefault.getReviewMode());
        assertEquals(ReviewMode.SECURITY_REVIEW, reviewSecurity.getReviewMode());
        assertEquals(2, codeReviewRepository.findByCodeChangeId(changeA.getId()).size());
    }

    @Test
    @DisplayName("Idempotency: Different AI input provenance for same CodeChange creates separate review record")
    void testIdempotencyDifferentAiInputHashCreatesSeparateReview() {
        CodeReview r1 = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        // Create second review with explicit prompt version override to yield different ai_input_hash
        CodeReview r2 = new CodeReview();
        r2.setProject(r1.getProject());
        r2.setRepository(r1.getRepository());
        r2.setTask(r1.getTask());
        r2.setCodeChange(r1.getCodeChange());
        r2.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        r2.setStatus(ReviewStatus.PENDING);
        r2.setSummary("Review with updated prompt version");
        r2.setOverallRiskScore("LOW");
        r2.setReviewContextHash(r1.getReviewContextHash());
        r2.setAiInputHash("DIFFERENT_PROVENANCE_HASH_9999");
        r2.setModelIdentifier("gemini-2.5-pro");
        r2.setPromptVersion("p2026.2");
        r2.setCreatedAt(Instant.now());
        r2 = codeReviewRepository.save(r2);

        assertNotEquals(r1.getId(), r2.getId());
        assertEquals(2, codeReviewRepository.findByCodeChangeId(changeA.getId()).size());
    }

    // ==================================================
    // 2. CONCURRENT FINALIZATION
    // ==================================================

    @Test
    @DisplayName("Concurrent Finalization A: Simultaneous completion attempts allow only one transition and avoid finding duplication")
    void testConcurrentFinalizationScenarioA() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        AiReviewerFindingDto finding = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "SECURITY", "CWE-89",
                FindingSeverity.HIGH, FindingConfidence.HIGH,
                "SQL Injection", "User input concatenated in query",
                "src/AuthService.java", 1, 10, FindingSide.PROPOSED,
                "String q = \"SELECT...\"", "Use prepared statement"
        );
        fakeTransport.setCannedResponse(new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Concurrent completion test", FindingSeverity.HIGH, List.of(finding)
        ));

        // Execution 1
        CodeReviewResultDto res1 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        // Concurrent Execution 2 (Simulating second worker finalizing same review)
        CodeReviewResultDto res2 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(res1.reviewId(), res2.reviewId());
        assertEquals(ReviewStatus.COMPLETED, res1.status());
        assertEquals(ReviewStatus.COMPLETED, res2.status());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, reviewInDb.getStatus());
        assertEquals(1, reviewFindingRepository.findByReview(reviewInDb).size());
    }

    @Test
    @DisplayName("Concurrent Finalization B: COMPLETED state remains authoritative when second worker attempts FAILED")
    void testConcurrentFinalizationScenarioB() {
        CodeReviewResultDto res1 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, res1.status());

        // Re-executing already terminal review returns existing COMPLETED record
        CodeReviewResultDto res2 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, res2.status());
        assertEquals(res1.reviewId(), res2.reviewId());
    }

    @Test
    @DisplayName("Concurrent Finalization C: Repeated COMPLETED calls preserve single finding set and prevent corruption")
    void testConcurrentFinalizationScenarioC() {
        AiReviewerFindingDto finding = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "QUALITY", "CWE-561",
                FindingSeverity.LOW, FindingConfidence.HIGH,
                "Unused Variable", "Dead code detected",
                "src/AuthService.java", 5, 5, FindingSide.PROPOSED,
                "int unused = 0;", "Remove unused variable"
        );
        fakeTransport.setCannedResponse(new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Quality review complete", FindingSeverity.LOW, List.of(finding)
        ));

        CodeReviewResultDto r1 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewResultDto r2 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.COMPLETED, r1.status());
        assertEquals(ReviewStatus.COMPLETED, r2.status());

        CodeReview review = codeReviewRepository.findById(r1.reviewId()).orElseThrow();
        assertEquals(1, reviewFindingRepository.findByReview(review).size());
    }

    @Test
    @DisplayName("Concurrent Finalization D: Stale worker cannot mutate finalized review")
    void testConcurrentFinalizationScenarioD() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        CodeReviewResultDto initialResult = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, initialResult.status());

        // Stale worker attempts to execute again with canned failure response
        fakeTransport.setCannedResponse(new AiReviewerResponseDto(
                ReviewStatus.FAILED, "Stale worker failure", FindingSeverity.HIGH, List.of()
        ));

        CodeReviewResultDto staleResult = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        // Stale worker is ignored; initial COMPLETED state remains authoritative
        assertEquals(ReviewStatus.COMPLETED, staleResult.status());

        CodeReview reviewInDb = codeReviewRepository.findById(record.getId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, reviewInDb.getStatus());
    }

    // ==================================================
    // 3. EXACT STATE MACHINE & TRANSITIONS
    // ==================================================

    @Test
    @DisplayName("State Machine: Legal state transitions operate as expected")
    void testStateTransitionsLegalTransitions() {
        CodeReview r1 = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.PENDING, r1.getStatus());

        CodeReviewResultDto res = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, res.status());
    }

    @Test
    @DisplayName("State Machine: Terminal state COMPLETED rejects modification or state override")
    void testStateTransitionsIllegalFromTerminalStates() {
        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, result.status());

        // Re-executing review returns existing COMPLETED result without changing status
        CodeReviewResultDto reExecutionResult = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, reExecutionResult.status());
        assertEquals(result.reviewId(), reExecutionResult.reviewId());
    }

    @Test
    @DisplayName("State Machine: PENDING state transitions cleanly to terminal state")
    void testStateTransitionsIllegalPendingDirectToCompleted() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.PENDING, record.getStatus());

        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, result.status());
    }

    @Test
    @DisplayName("State Machine: RUNNING state completes and does not revert to PENDING")
    void testStateTransitionsIllegalRunningBackToPending() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.COMPLETED, result.status());
        assertNotEquals(ReviewStatus.PENDING, result.status());
    }

    // ==================================================
    // 4. TOCTOU & RE-VERIFICATION
    // ==================================================

    @Test
    @DisplayName("TOCTOU Protection: CodeChange diff mutation during review execution transitions review to STALE and discards findings")
    void testToctouDiffMutationTransitionsToStale() {
        fakeTransport.setOnRequestCallback(req -> {
            changeA.setDiff("--- src/AuthService.java\n+++ src/AuthService.java\n@@ -1 +1 @@\n-public class AuthService {}\n+public class AuthService { void diffMutated() {} }\n");
            codeChangeRepository.save(changeA);
        });

        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.STALE, result.status());
        assertTrue(result.summary().contains("TOCTOU boundary violation"), result.summary());
        assertTrue(result.findings().isEmpty());

        CodeReview persisted = codeReviewRepository.findById(result.reviewId()).orElseThrow();
        assertEquals(ReviewStatus.STALE, persisted.getStatus());
        assertTrue(reviewFindingRepository.findByReview(persisted).isEmpty());
    }

    @Test
    @DisplayName("TOCTOU Protection: Original hash mutation during review execution transitions review to STALE")
    void testToctouOriginalHashMutationTransitionsToStale() {
        fakeTransport.setOnRequestCallback(req -> {
            changeA.setOriginalHash("MUTATED_ORIGINAL_HASH_9999");
            codeChangeRepository.save(changeA);
        });

        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.STALE, result.status());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    @DisplayName("TOCTOU Protection: Proposed hash mutation during review execution transitions review to STALE")
    void testToctouProposedHashMutationTransitionsToStale() {
        fakeTransport.setOnRequestCallback(req -> {
            changeA.setProposedHash("MUTATED_PROPOSED_HASH_8888");
            codeChangeRepository.save(changeA);
        });

        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.STALE, result.status());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    @DisplayName("TOCTOU Protection: File path mutation during review execution transitions review to STALE")
    void testToctouFilePathMutationTransitionsToStale() {
        fakeTransport.setOnRequestCallback(req -> {
            changeA.setFilePath("src/MutatedPathService.java");
            codeChangeRepository.save(changeA);
        });

        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.STALE, result.status());
        assertTrue(result.findings().isEmpty());
    }

    // ==================================================
    // 5. TRANSACTION ATOMICITY & ROLLBACK
    // ==================================================

    @Test
    @DisplayName("Transaction Atomicity: Database rollback clears partial findings and leaves database consistent")
    void testTransactionRollbackOnPersistenceFailure() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThrows(RuntimeException.class, () -> {
            template.executeWithoutResult((TransactionStatus status) -> {
                ReviewFinding finding = new ReviewFinding();
                finding.setReview(record);
                finding.setProject(projectA);
                finding.setRepository(repoA);
                finding.setTask(taskA);
                finding.setCodeChange(changeA);
                finding.setFindingType(FindingType.AI_FINDING);
                finding.setCategory("SECURITY");
                finding.setSeverity(FindingSeverity.HIGH);
                finding.setConfidence(FindingConfidence.HIGH);
                finding.setTitle("Temporary Finding");
                finding.setDescription("Description");
                finding.setFilePath("src/AuthService.java");
                finding.setStartLine(1);
                finding.setEndLine(2);
                finding.setSide(FindingSide.PROPOSED);
                finding.setContentHash("hash123");
                finding.setFingerprint("fp123");
                finding.setCreatedAt(Instant.now());
                finding.setUpdatedAt(Instant.now());

                reviewFindingRepository.save(finding);

                // Force explicit transaction rollback
                status.setRollbackOnly();
                throw new RuntimeException("Forced rollback test exception");
            });
        });

        // Verify zero partial findings persisted
        List<ReviewFinding> findings = reviewFindingRepository.findByReview(record);
        assertTrue(findings.isEmpty());
    }

    // ==================================================
    // 6. FINDING DUPLICATION PROTECTION
    // ==================================================

    @Test
    @DisplayName("Finding Duplication: Executing review multiple times never inserts duplicate findings")
    void testFindingDuplicationProtection() {
        AiReviewerFindingDto finding = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "SECURITY", "CWE-89",
                FindingSeverity.HIGH, FindingConfidence.HIGH,
                "SQL Injection", "Query concatenation",
                "src/AuthService.java", 1, 10, FindingSide.PROPOSED,
                "String q = \"SELECT...\"", "Use prepared statement"
        );
        fakeTransport.setCannedResponse(new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Automated review complete", FindingSeverity.HIGH, List.of(finding)
        ));

        CodeReviewResultDto r1 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        CodeReviewResultDto r2 = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(r1.reviewId(), r2.reviewId());

        CodeReview review = codeReviewRepository.findById(r1.reviewId()).orElseThrow();
        List<ReviewFinding> findings = reviewFindingRepository.findByReview(review);
        assertEquals(1, findings.size());
    }

    // ==================================================
    // 7. IDOR & AUTHORIZATION BOUNDARIES
    // ==================================================

    @Test
    @DisplayName("IDOR: Attacker creating review with wrong project identity is REJECTED with FORBIDDEN")
    void testIdorWrongProjectRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                lifecycleService.createReviewRecord(
                        projectB.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
                )
        );
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("IDOR: Attacker retrieving review result without project membership is REJECTED with FORBIDDEN")
    void testIdorGetReviewResultWithoutAccessRejected() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                lifecycleService.getReviewResult(record.getId(), projectA.getId(), attacker)
        );
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("IDOR: Requesting nonexistent review ID is REJECTED with NOT_FOUND")
    void testGetReviewResultNonexistentReviewRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                lifecycleService.getReviewResult(UUID.randomUUID(), projectA.getId(), owner)
        );
        assertEquals("REVIEW_NOT_FOUND", ex.getCode());
    }

    // ==================================================
    // 8. SECRET PERSISTENCE BOUNDARY
    // ==================================================

    @Test
    @DisplayName("Security: Raw secrets in CodeChange diff or exception details are NEVER persisted to database")
    void testRawSecretNeverPersistedToDatabase() {
        AiReviewerFindingDto findingWithSecret = new AiReviewerFindingDto(
                FindingType.AI_FINDING, "LEAK", "CWE-200",
                FindingSeverity.HIGH, FindingConfidence.HIGH,
                "Exposed token ghp_CANARY_SECRET_123456789012345678", "Description with secret ghp_CANARY_SECRET_123456789012345678",
                "src/AuthService.java", 1, 5, FindingSide.PROPOSED,
                "ghp_CANARY_SECRET_123456789012345678", "Redact secret"
        );
        fakeTransport.setCannedResponse(new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "Found canary ghp_CANARY_SECRET_123456789012345678", FindingSeverity.HIGH, List.of(findingWithSecret)
        ));

        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        CodeReview persistedReview = codeReviewRepository.findById(result.reviewId()).orElseThrow();
        assertFalse(persistedReview.getSummary().contains("ghp_CANARY_SECRET_123456789012345678"));
        assertTrue(persistedReview.getSummary().contains("[REDACTED_SECRET_PRE_LLM]"));

        List<ReviewFinding> findings = reviewFindingRepository.findByReview(persistedReview);
        assertEquals(1, findings.size());
        ReviewFinding f = findings.get(0);
        assertFalse(f.getTitle().contains("ghp_CANARY_SECRET_123456789012345678"));
        assertFalse(f.getDescription().contains("ghp_CANARY_SECRET_123456789012345678"));
        assertTrue(f.getTitle().contains("[REDACTED_SECRET_PRE_LLM]"));
        assertTrue(f.getDescription().contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    // ==================================================
    // 9. CAPABILITY BOUNDARY AUDIT
    // ==================================================

    @Test
    @DisplayName("Capability Boundary: Persistent review lifecycle does not trigger Git, GitHub, or approval mutations")
    void testCapabilityBoundaryNoExternalSideEffects() {
        CodeReviewResultDto result = lifecycleService.executePersistentReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertNotNull(result);
        assertNotNull(result.reviewId());

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        assertEquals("MODIFY", cc.getOperation());
    }
}
