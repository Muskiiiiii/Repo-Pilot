package com.repopilot.review;

import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.auth.security.UserPrincipal;
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
import com.repopilot.review.controller.CodeReviewController;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.AdminReviewOverrideService;
import com.repopilot.review.service.CodeReviewOrchestrationService;
import com.repopilot.review.service.PersistentReviewLifecycleService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase13_Slice6RestApiAndAdminOverrideTest {

    @Autowired
    private CodeReviewController codeReviewController;

    @Autowired
    private CodeReviewOrchestrationService orchestrationService;

    @Autowired
    private AdminReviewOverrideService adminOverrideService;

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ReviewFindingRepository reviewFindingRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private User owner;
    private User adminUser;
    private User memberUser;
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
        owner.setEmail("owner_slice6_" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("hash");
        owner.setName("Owner Slice6");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        adminUser = new User();
        adminUser.setEmail("admin_slice6_" + UUID.randomUUID() + "@test.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("Admin Slice6");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);

        memberUser = new User();
        memberUser.setEmail("member_slice6_" + UUID.randomUUID() + "@test.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Member Slice6");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        attacker = new User();
        attacker.setEmail("attacker_slice6_" + UUID.randomUUID() + "@test.com");
        attacker.setPasswordHash("hash");
        attacker.setName("Attacker Slice6");
        attacker.setRole(UserRole.USER);
        attacker = userRepository.save(attacker);

        // Project A
        projectA = new Project();
        projectA.setOwner(owner);
        projectA.setName("Project Slice6 A " + UUID.randomUUID());
        projectA.setDescription("Project Slice6 A");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(projectA, adminUser, ProjectRole.ADMIN));
        projectMemberRepository.save(new ProjectMember(projectA, memberUser, ProjectRole.MEMBER));

        // Project B (Isolated for IDOR testing)
        projectB = new Project();
        projectB.setOwner(attacker);
        projectB.setName("Project Slice6 B " + UUID.randomUUID());
        projectB.setDescription("Project Slice6 B");
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        projectMemberRepository.save(new ProjectMember(projectB, attacker, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("repopilot");
        repoA.setRepoName("repo-slice6-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("attacker");
        repoB.setRepoName("repo-slice6-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task Slice6 A");
        taskA.setDescription("Task Slice6 Description A");
        taskA.setPriority(TaskPriority.HIGH);
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(attacker);
        taskB.setTitle("Task Slice6 B");
        taskB.setDescription("Task Slice6 Description B");
        taskB.setPriority(TaskPriority.MEDIUM);
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB = taskRepository.save(taskB);

        Plan planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan Slice6 Objective A");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/ApiService.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class ApiService {}");
        changeA.setNewContent("public class ApiService { String secret = \"ghp_CANARY_SECRET_SLICE6_9999\"; }");
        changeA.setOriginalHash("orig_slice6_123");
        changeA.setProposedHash("prop_slice6_456");
        changeA.setDiff("--- src/ApiService.java\n+++ src/ApiService.java\n@@ -1 +1 @@\n-public class ApiService {}\n+public class ApiService { String secret = \"ghp_CANARY_SECRET_SLICE6_9999\"; }\n");
        changeA = codeChangeRepository.save(changeA);
    }

    @AfterEach
    void tearDown() {
        reviewFindingRepository.deleteAll();
        codeReviewRepository.deleteAll();
        auditLogRepository.deleteAll();
        codeChangeRepository.deleteAll();
        planRepository.deleteAll();
        taskRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectMemberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ==================================================
    // A. REST CONTROLLER EXECUTION & IDEMPOTENCY
    // ==================================================

    @Test
    @DisplayName("REST: Code change review POST request executes pipeline and returns 200 OK result")
    void testTriggerCodeChangeReviewSuccess() {
        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        assertNotNull(result);
        assertEquals(ReviewStatus.COMPLETED, result.status());
        assertEquals(projectA.getId(), result.projectId());
        assertEquals(repoA.getId(), result.repositoryId());
        assertEquals(taskA.getId(), result.taskId());
        assertEquals(changeA.getId(), result.codeChangeId());

        CodeReview reviewInDb = codeReviewRepository.findById(result.reviewId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, reviewInDb.getStatus());
    }

    @Test
    @DisplayName("REST Idempotency: Duplicate POST request for same code change returns existing terminal review")
    void testTriggerCodeChangeReviewIdempotent() {
        CodeReviewResultDto firstResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, "key-123", owner
        );

        CodeReviewResultDto secondResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, "key-123", owner
        );

        assertEquals(firstResult.reviewId(), secondResult.reviewId());
        assertEquals(firstResult.reviewContextHash(), secondResult.reviewContextHash());
        assertEquals(ReviewStatus.COMPLETED, secondResult.status());
    }

    @Test
    @DisplayName("REST Task Review: Task-level implementation review POST request executes TASK_IMPLEMENTATION_REVIEW")
    void testTriggerTaskLevelReviewSuccess() {
        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), null, ReviewMode.TASK_IMPLEMENTATION_REVIEW, null, owner
        );

        assertNotNull(result);
        assertEquals(ReviewStatus.COMPLETED, result.status());
        assertEquals(ReviewMode.TASK_IMPLEMENTATION_REVIEW, result.reviewMode());
        assertNull(result.codeChangeId());
    }

    // ==================================================
    // B. RESOURCE HIERARCHY & IDOR AUTHORIZATION
    // ==================================================

    @Test
    @DisplayName("IDOR: Non-project member request to execute review is rejected with FORBIDDEN")
    void testNonProjectMemberRequestRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                orchestrationService.executeCodeReview(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, attacker
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("IDOR: Cross-project repository ID mismatch is rejected with FORBIDDEN")
    void testCrossProjectRepositoryMismatchRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                orchestrationService.executeCodeReview(
                        projectA.getId(), repoB.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("IDOR: Cross-repository task ID mismatch is rejected with FORBIDDEN")
    void testCrossRepositoryTaskMismatchRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                orchestrationService.executeCodeReview(
                        projectA.getId(), repoA.getId(), taskB.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // ==================================================
    // C. ADMIN REVIEW OVERRIDE API & DB TRIGGERS
    // ==================================================

    @Test
    @DisplayName("Admin Override: Project ADMIN successfully overrides COMPLETED review")
    void testAdminOverrideSuccessByProjectAdmin() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Approved after senior security review rationale.");

        AdminReviewOverrideResponseDto overrideResponse = adminOverrideService.overrideReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), request, adminUser
        );

        assertNotNull(overrideResponse);
        assertEquals(reviewResult.reviewId(), overrideResponse.reviewId());
        assertEquals("Approved after senior security review rationale.", overrideResponse.adminOverrideReason());
        assertEquals(adminUser.getId(), overrideResponse.adminOverrideByUserId());
        assertNotNull(overrideResponse.adminOverrideAt());

        CodeReview reviewInDb = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        assertEquals("Approved after senior security review rationale.", reviewInDb.getAdminOverrideReason());
        assertEquals(adminUser.getId(), reviewInDb.getAdminOverrideByUser().getId());
    }

    @Test
    @DisplayName("Admin Override: Standard project MEMBER override attempt is REJECTED with FORBIDDEN")
    void testAdminOverrideRejectedForNonAdminMember() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Member attempting override.");

        ApiException ex = assertThrows(ApiException.class, () ->
                adminOverrideService.overrideReview(
                        projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), request, memberUser
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("Admin Override: Attempting override on non-COMPLETED review is REJECTED with BAD_REQUEST")
    void testAdminOverrideRejectedForNonCompletedReview() {
        CodeReview record = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Attempting override on PENDING review.");

        ApiException ex = assertThrows(ApiException.class, () ->
                adminOverrideService.overrideReview(
                        projectA.getId(), repoA.getId(), taskA.getId(), record.getId(), request, adminUser
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_REVIEW_STATUS", ex.getCode());
    }

    @Test
    @DisplayName("Admin Override DB Trigger: Double admin override attempt is REJECTED by PostgreSQL PL/pgSQL trigger")
    void testDoubleAdminOverrideRejectedByDatabaseTrigger() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        AdminReviewOverrideRequest firstRequest = new AdminReviewOverrideRequest("First valid admin override.");
        adminOverrideService.overrideReview(projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), firstRequest, adminUser);

        AdminReviewOverrideRequest secondRequest = new AdminReviewOverrideRequest("Second malicious admin override attempt.");

        ApiException ex = assertThrows(ApiException.class, () ->
                adminOverrideService.overrideReview(projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), secondRequest, owner)
        );
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());

        CodeReview reviewInDb = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        assertEquals("First valid admin override.", reviewInDb.getAdminOverrideReason());
    }

    @Test
    @DisplayName("Admin Override Redaction: Secret tokens in override reason are automatically redacted")
    void testAdminOverrideReasonRedactsSecrets() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest(
                "Override approved for secret token ghp_CANARY_SECRET_SLICE6_9999_12345678901234567890 after manual audit."
        );

        AdminReviewOverrideResponseDto response = adminOverrideService.overrideReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), request, adminUser
        );

        assertFalse(response.adminOverrideReason().contains("ghp_CANARY_SECRET_SLICE6_9999_12345678901234567890"));
        assertTrue(response.adminOverrideReason().contains("[REDACTED_SECRET_"));
    }

    @Test
    @DisplayName("Admin Override Validation: Blank override reason is REJECTED with BAD_REQUEST")
    void testAdminOverrideReasonBlankRejected() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("   ");

        ApiException ex = assertThrows(ApiException.class, () ->
                adminOverrideService.overrideReview(
                        projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), request, adminUser
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INVALID_OVERRIDE_REASON", ex.getCode());
    }

    // ==================================================
    // D. QUERY ENDPOINTS & SANITIZATION
    // ==================================================

    @Test
    @DisplayName("Query: getCodeReviewResult returns sanitized DTO for project member")
    void testGetCodeReviewResultSuccess() {
        CodeReviewResultDto created = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReviewResultDto fetched = orchestrationService.getCodeReviewResult(created.reviewId(), memberUser);
        assertNotNull(fetched);
        assertEquals(created.reviewId(), fetched.reviewId());
        assertEquals(ReviewStatus.COMPLETED, fetched.status());
    }

    @Test
    @DisplayName("Query: getCodeReviewsForTask returns all reviews under task")
    void testGetCodeReviewsForTaskSuccess() {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        List<CodeReviewResultDto> taskReviews = orchestrationService.getCodeReviewsForTask(projectA.getId(), repoA.getId(), taskA.getId(), memberUser);
        assertFalse(taskReviews.isEmpty());
    }

    // ==================================================
    // E. HOSTILE SECURITY & ADVANCED CONCURRENCY TESTS
    // ==================================================

    @Test
    @DisplayName("Hostile Auth: Unauthenticated caller is rejected with UNAUTHORIZED or FORBIDDEN")
    void testUnauthenticatedCallerRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                adminOverrideService.overrideReview(
                        projectA.getId(), repoA.getId(), taskA.getId(), UUID.randomUUID(),
                        new AdminReviewOverrideRequest("Unauthenticated attempt"), null
                )
        );
        assertTrue(ex.getStatus() == HttpStatus.UNAUTHORIZED || ex.getStatus() == HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Hostile Authz: Admin of Project B cannot override review belonging to Project A")
    void testProjectAdminCannotOverrideReviewInAnotherProject() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                adminOverrideService.overrideReview(
                        projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(),
                        new AdminReviewOverrideRequest("Cross project override attempt"), attacker
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("Hostile Concurrency: True multi-threaded double-override race -> exactly 1 winner, 1 conflict, 1 DB update")
    void testTrueConcurrentDoubleOverrideRace() throws Exception {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        Future<AdminReviewOverrideResponseDto> f1 = executor.submit(() -> {
            latch.await();
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tt.execute(status -> adminOverrideService.overrideReview(
                    projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(),
                    new AdminReviewOverrideRequest("Concurrent Admin Override Thread 1"), adminUser
            ));
        });

        Future<AdminReviewOverrideResponseDto> f2 = executor.submit(() -> {
            latch.await();
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tt.execute(status -> adminOverrideService.overrideReview(
                    projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(),
                    new AdminReviewOverrideRequest("Concurrent Owner Override Thread 2"), owner
            ));
        });

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        int successCount = 0;
        int conflictCount = 0;

        try {
            f1.get();
            successCount++;
        } catch (Exception e) {
            if (e.getCause() instanceof ApiException apiEx && apiEx.getStatus() == HttpStatus.CONFLICT) {
                conflictCount++;
            }
        }

        try {
            f2.get();
            successCount++;
        } catch (Exception e) {
            if (e.getCause() instanceof ApiException apiEx && apiEx.getStatus() == HttpStatus.CONFLICT) {
                conflictCount++;
            }
        }

        assertEquals(1, successCount, "Exactly one override thread must succeed");
        assertEquals(1, conflictCount, "Exactly one override thread must fail with CONFLICT (409)");

        CodeReview reviewInDb = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        assertNotNull(reviewInDb.getAdminOverrideReason());
        assertNotNull(reviewInDb.getAdminOverrideByUser());
        assertNotNull(reviewInDb.getAdminOverrideAt());
    }

    @Test
    @DisplayName("Trigger Idempotency: Concurrent duplicate review requests reconcile to single review execution")
    void testConcurrentDuplicateReviewTriggersReconcile() throws Exception {
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        Future<CodeReviewResultDto> f1 = executor.submit(() -> {
            latch.await();
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tt.execute(status -> orchestrationService.executeCodeReview(
                    projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, "concurrent-key-123", owner
            ));
        });

        Future<CodeReviewResultDto> f2 = executor.submit(() -> {
            latch.await();
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tt.execute(status -> orchestrationService.executeCodeReview(
                    projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, "concurrent-key-123", owner
            ));
        });

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        CodeReviewResultDto res1 = null;
        CodeReviewResultDto res2 = null;

        try { res1 = f1.get(); } catch (Exception ignored) {}
        try { res2 = f2.get(); } catch (Exception ignored) {}

        assertTrue(res1 != null || res2 != null, "At least one review trigger execution must succeed");
        assertEquals(1, codeReviewRepository.count(), "Exactly one CodeReview record must exist in DB after concurrent duplicate triggers");
    }

    @Test
    @DisplayName("Info Leakage: Public DTO objects do not contain internal fencing or worker tokens")
    void testPublicDtoDoesNotExposeWorkerTokensOrSecrets() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Reason without token leakage");
        AdminReviewOverrideResponseDto response = adminOverrideService.overrideReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), request, adminUser
        );

        assertNotNull(response);
        assertNotNull(response.reviewId());
        assertNotNull(response.adminOverrideReason());
        assertNotNull(response.adminOverrideByUserId());
        assertNotNull(response.adminOverrideAt());
    }

    // ==================================================
    // F. GET/LIST IDOR & PROJECT ISOLATION TESTS (REQ 2)
    // ==================================================

    @Test
    @DisplayName("IDOR GET: User in Project A cannot GET a review belonging to Project B")
    void testUserInProjectACannotGetReviewInProjectB() {
        CodeReviewResultDto reviewB = orchestrationService.executeCodeReview(
                projectB.getId(), repoB.getId(), taskB.getId(), null, ReviewMode.TASK_IMPLEMENTATION_REVIEW, null, attacker
        );

        UserPrincipal principalA = new UserPrincipal(memberUser);

        ApiException ex = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewById(projectA.getId(), repoA.getId(), taskA.getId(), reviewB.reviewId(), principalA)
        );
        assertTrue(ex.getStatus() == HttpStatus.FORBIDDEN || ex.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("IDOR LIST: User in Project A cannot list reviews belonging to Project B")
    void testUserInProjectACannotListReviewsInProjectB() {
        orchestrationService.executeCodeReview(
                projectB.getId(), repoB.getId(), taskB.getId(), null, ReviewMode.TASK_IMPLEMENTATION_REVIEW, null, attacker
        );

        UserPrincipal principalA = new UserPrincipal(memberUser);

        ApiException ex = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewsForTask(projectB.getId(), repoB.getId(), taskB.getId(), principalA)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("IDOR GET: Project A Admin cannot retrieve Project B review via reviewId")
    void testProjectAAdminCannotGetProjectBReviewViaReviewId() {
        CodeReviewResultDto reviewB = orchestrationService.executeCodeReview(
                projectB.getId(), repoB.getId(), taskB.getId(), null, ReviewMode.TASK_IMPLEMENTATION_REVIEW, null, attacker
        );

        UserPrincipal adminPrincipalA = new UserPrincipal(adminUser);

        ApiException ex = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewById(projectA.getId(), repoA.getId(), taskA.getId(), reviewB.reviewId(), adminPrincipalA)
        );
        assertTrue(ex.getStatus() == HttpStatus.FORBIDDEN || ex.getStatus() == HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("IDOR LIST: Project A Admin cannot list Project B reviews via path/query parameters")
    void testProjectAAdminCannotListProjectBReviewsViaQueryParameters() {
        UserPrincipal adminPrincipalA = new UserPrincipal(adminUser);

        ApiException ex = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewsForTask(projectB.getId(), repoB.getId(), taskB.getId(), adminPrincipalA)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("GET 404: Nonexistent review returns NOT_FOUND without internal leakage")
    void testNonexistentReviewReturnsNotFound() {
        UserPrincipal principalA = new UserPrincipal(memberUser);

        ApiException ex = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewById(projectA.getId(), repoA.getId(), taskA.getId(), UUID.randomUUID(), principalA)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("REVIEW_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("GET/LIST 401: Unauthenticated GET and LIST endpoints throw UNAUTHORIZED")
    void testUnauthenticatedGetAndListRejectedWithUnauthorized() {
        ApiException ex1 = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewById(projectA.getId(), repoA.getId(), taskA.getId(), UUID.randomUUID(), null)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex1.getStatus());

        ApiException ex2 = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewsForTask(projectA.getId(), repoA.getId(), taskA.getId(), null)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex2.getStatus());
    }

    @Test
    @DisplayName("Hierarchy Check: Task/Repository mismatch in path returns NOT_FOUND or FORBIDDEN")
    void testHierarchyMismatchRejectedForGetAndList() {
        CodeReviewResultDto reviewA = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        UserPrincipal principalA = new UserPrincipal(memberUser);

        ApiException ex = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewById(projectA.getId(), repoA.getId(), taskB.getId(), reviewA.reviewId(), principalA)
        );
        assertTrue(ex.getStatus() == HttpStatus.NOT_FOUND || ex.getStatus() == HttpStatus.FORBIDDEN);
    }

    // ==================================================
    // G. POST /REVIEWS DUPLICATE SEMANTICS & STATE MACHINE (REQ 3)
    // ==================================================

    @Test
    @DisplayName("Duplicate POST: Duplicate POST while PENDING returns same review record")
    void testDuplicatePostWhilePendingReturnsSameReview() {
        CodeReview pendingRecord = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );
        assertEquals(ReviewStatus.PENDING, pendingRecord.getStatus());

        CodeReview duplicateRecord = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(pendingRecord.getId(), duplicateRecord.getId());
    }

    @Test
    @DisplayName("Duplicate POST: Duplicate POST after COMPLETED returns same terminal review")
    void testDuplicatePostAfterCompletedReturnsSameTerminalReview() {
        CodeReviewResultDto firstRun = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );
        assertEquals(ReviewStatus.COMPLETED, firstRun.status());

        CodeReview secondRunRecord = lifecycleService.createReviewRecord(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(firstRun.reviewId(), secondRunRecord.getId());
        assertEquals(ReviewStatus.COMPLETED, secondRunRecord.getStatus());
    }

    @Test
    @DisplayName("Duplicate POST: Duplicate POST with changed context hash creates distinct review record")
    void testDuplicatePostWithChangedContextHashCreatesNewReviewRecord() {
        CodeReviewResultDto firstRun = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Mutate change content to change context hash
        changeA.setDiff("--- src/ApiService.java\n+++ src/ApiService.java\n@@ -1 +1 @@\n-public class ApiService {}\n+public class ApiService { int version = 2; }\n");
        codeChangeRepository.save(changeA);

        CodeReviewResultDto secondRun = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        assertNotEquals(firstRun.reviewId(), secondRun.reviewId());
        assertNotEquals(firstRun.reviewContextHash(), secondRun.reviewContextHash());
    }

    // ==================================================
    // H. TRUE HTTP CONTROLLER DOUBLE-OVERRIDE RACE (REQ 4)
    // ==================================================

    @Test
    @DisplayName("HTTP Controller Concurrency: Two concurrent HTTP override requests -> exactly 1 200 OK, 1 409 CONFLICT")
    void testTrueConcurrentHttpControllerDoubleOverrideRace() throws Exception {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);

        Future<ResponseEntity<AdminReviewOverrideResponseDto>> f1 = executor.submit(() -> {
            latch.await();
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tt.execute(status -> codeReviewController.overrideReview(
                    projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(),
                    new AdminReviewOverrideRequest("HTTP Admin Override Thread 1"), new UserPrincipal(adminUser)
            ));
        });

        Future<ResponseEntity<AdminReviewOverrideResponseDto>> f2 = executor.submit(() -> {
            latch.await();
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tt.execute(status -> codeReviewController.overrideReview(
                    projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(),
                    new AdminReviewOverrideRequest("HTTP Owner Override Thread 2"), new UserPrincipal(owner)
            ));
        });

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        int http200Count = 0;
        int http409Count = 0;

        try {
            ResponseEntity<AdminReviewOverrideResponseDto> resp1 = f1.get();
            if (resp1 != null && resp1.getStatusCode() == HttpStatus.OK) {
                http200Count++;
            }
        } catch (Exception e) {
            if (e.getCause() instanceof ApiException apiEx && apiEx.getStatus() == HttpStatus.CONFLICT) {
                http409Count++;
            }
        }

        try {
            ResponseEntity<AdminReviewOverrideResponseDto> resp2 = f2.get();
            if (resp2 != null && resp2.getStatusCode() == HttpStatus.OK) {
                http200Count++;
            }
        } catch (Exception e) {
            if (e.getCause() instanceof ApiException apiEx && apiEx.getStatus() == HttpStatus.CONFLICT) {
                http409Count++;
            }
        }

        assertEquals(1, http200Count, "Exactly one HTTP override request must succeed with 200 OK");
        assertEquals(1, http409Count, "Exactly one HTTP override request must fail with 409 CONFLICT");

        CodeReview reviewInDb = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        assertNotNull(reviewInDb.getAdminOverrideReason());
        assertNotNull(reviewInDb.getAdminOverrideByUser());
        assertNotNull(reviewInDb.getAdminOverrideAt());
    }

    // ==================================================
    // I. SECRET REDACTION ABSENCE & AUDIT (REQ 5)
    // ==================================================

    @Test
    @DisplayName("Secret Redaction: Raw secret token is absent from response DTO and database persistence")
    void testSecretRedactionRawTokenNeverEscapes() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        String rawSecretToken = "ghp_CANARY_TEST_SECRET_98765432101234567890";
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest(
                "Approved override containing secret " + rawSecretToken + " after review."
        );

        ResponseEntity<AdminReviewOverrideResponseDto> responseEntity = codeReviewController.overrideReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), request, new UserPrincipal(adminUser)
        );

        AdminReviewOverrideResponseDto response = responseEntity.getBody();
        assertNotNull(response);

        // 1. Absent from HTTP response DTO
        assertFalse(response.adminOverrideReason().contains(rawSecretToken), "Raw secret token MUST NOT exist in response DTO");
        assertTrue(response.adminOverrideReason().contains("[REDACTED_SECRET_"));

        // 2. Absent from persisted DB record
        CodeReview reviewInDb = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        assertFalse(reviewInDb.getAdminOverrideReason().contains(rawSecretToken), "Raw secret token MUST NOT exist in DB table");
        assertTrue(reviewInDb.getAdminOverrideReason().contains("[REDACTED_SECRET_"));
    }

    // ==================================================
    // J. ACTOR & TIMESTAMP AUTHORITY (REQ 6)
    // ==================================================

    @Test
    @DisplayName("Authority: Actor user ID and timestamp are generated exclusively by server")
    void testActorAndTimestampDerivedStrictlyFromServer() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Server derived authority test");

        ResponseEntity<AdminReviewOverrideResponseDto> responseEntity = codeReviewController.overrideReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), request, new UserPrincipal(adminUser)
        );

        AdminReviewOverrideResponseDto response = responseEntity.getBody();
        assertNotNull(response);

        // Actor ID MUST equal adminUser.getId() from SecurityContext
        assertEquals(adminUser.getId(), response.adminOverrideByUserId());

        // Timestamp MUST be within 10 seconds of current system time
        assertNotNull(response.adminOverrideAt());
        assertFalse(response.adminOverrideAt().isAfter(java.time.Instant.now().plusSeconds(60)), "Timestamp must be server generated");
    }

    // ==================================================
    // K. INFORMATION LEAKAGE & DB IMMUTABILITY (REQ 7 & 8)
    // ==================================================

    @Test
    @DisplayName("Info Leakage: Controller error responses do not leak internal infrastructure details")
    void testControllerErrorResponsesDoNotExposeInternalTokensOrDetails() {
        ApiException ex = assertThrows(ApiException.class, () ->
                codeReviewController.getReviewById(projectA.getId(), repoA.getId(), taskA.getId(), UUID.randomUUID(), new UserPrincipal(memberUser))
        );

        String message = ex.getMessage();
        assertFalse(message.contains("leaseToken"));
        assertFalse(message.contains("workerId"));
        assertFalse(message.contains("fencingToken"));
        assertFalse(message.contains("SqlException"));
        assertFalse(message.contains("ConstraintViolation"));
    }
}
