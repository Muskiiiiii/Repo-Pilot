package com.repopilot.review;

import com.repopilot.approval.dto.HumanApprovalCreateRequest;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.repository.PublicationAuditEventRepository;
import com.repopilot.approval.service.HumanApprovalService;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
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
import com.repopilot.repository.entity.RepositoryBranch;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryBranchRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.*;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase13_Slice8CoreReviewSummaryEngineTest {

    @Autowired
    private CodeReviewSummaryReportService summaryReportService;

    @Autowired
    private CodeReviewOrchestrationService orchestrationService;

    @Autowired
    private AdminReviewOverrideService adminOverrideService;

    @Autowired
    private HumanApprovalService humanApprovalService;

    @Autowired
    private DiffGeneratorService diffGeneratorService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryBranchRepository repositoryBranchRepository;

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
    private HumanApprovalRepository humanApprovalRepository;

    @Autowired
    private PublicationAuditEventRepository publicationAuditEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private com.repopilot.review.transport.DeterministicFakeAiReviewerTransport fakeTransport;

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
    private Plan planA;
    private CodeChange changeA;

    @BeforeEach
    void setUp() {
        fakeTransport.reset();
        owner = new User();
        owner.setEmail("owner_slice8_" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("hash");
        owner.setName("Owner Slice8");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        adminUser = new User();
        adminUser.setEmail("admin_slice8_" + UUID.randomUUID() + "@test.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("Admin Slice8");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);

        memberUser = new User();
        memberUser.setEmail("member_slice8_" + UUID.randomUUID() + "@test.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Member Slice8");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        attacker = new User();
        attacker.setEmail("attacker_slice8_" + UUID.randomUUID() + "@test.com");
        attacker.setPasswordHash("hash");
        attacker.setName("Attacker Slice8");
        attacker.setRole(UserRole.USER);
        attacker = userRepository.save(attacker);

        // Project A
        projectA = new Project();
        projectA.setOwner(owner);
        projectA.setName("Project Slice8 A " + UUID.randomUUID());
        projectA.setDescription("Project Slice8 A");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(projectA, adminUser, ProjectRole.ADMIN));
        projectMemberRepository.save(new ProjectMember(projectA, memberUser, ProjectRole.MEMBER));

        // Project B
        projectB = new Project();
        projectB.setOwner(attacker);
        projectB.setName("Project Slice8 B " + UUID.randomUUID());
        projectB.setDescription("Project Slice8 B");
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);
        projectMemberRepository.save(new ProjectMember(projectB, attacker, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("repopilot");
        repoA.setRepoName("repo-slice8-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        RepositoryBranch branchA = new RepositoryBranch();
        branchA.setRepository(repoA);
        branchA.setName("main");
        branchA.setCommitSha("1111222233334444555566667777888899990000");
        branchA.setIsDefault(true);
        repositoryBranchRepository.save(branchA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("attacker");
        repoB.setRepoName("repo-slice8-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task Slice8 A");
        taskA.setDescription("Task Slice8 Description A");
        taskA.setPriority(TaskPriority.HIGH);
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(attacker);
        taskB.setTitle("Task Slice8 B");
        taskB.setDescription("Task Slice8 Description B");
        taskB.setPriority(TaskPriority.MEDIUM);
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB = taskRepository.save(taskB);

        planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan Slice8 Objective A");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/SecurityGateService.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class SecurityGateService {}");
        changeA.setNewContent("public class SecurityGateService { void check() {} }");
        changeA.setOriginalHash("orig_slice8_123");
        changeA.setProposedHash("prop_slice8_456");
        changeA.setDiff("--- src/SecurityGateService.java\n+++ src/SecurityGateService.java\n@@ -1 +1 @@\n-public class SecurityGateService {}\n+public class SecurityGateService { void check() {} }\n");
        changeA.setStatus("GENERATED");
        changeA = codeChangeRepository.save(changeA);
    }

    @AfterEach
    void tearDown() {
        publicationAuditEventRepository.deleteAll();
        humanApprovalRepository.deleteAll();
        reviewFindingRepository.deleteAll();
        codeReviewRepository.deleteAll();
        auditLogRepository.deleteAll();
        codeChangeRepository.deleteAll();
        planRepository.deleteAll();
        taskRepository.deleteAll();
        repositoryBranchRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectMemberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Slice 8 Engine 1: testGeneratedCodeChangeSummary")
    void testGeneratedCodeChangeSummary() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertNotNull(summary);
        assertEquals("GENERATED", summary.codeChangeStatus());
        assertTrue(summary.isCodeChangeActive());
        assertFalse(summary.isAlreadyApproved());
        assertTrue(summary.eligibleForApproval());
        assertTrue(summary.eligibleForNewApproval());
        assertFalse(summary.isContextStale());
        assertEquals(0, summary.findingsSummary().totalCount());
    }

    @Test
    @DisplayName("Slice 8 Engine 2: testApprovedCodeChangeSummary")
    void testApprovedCodeChangeSummary() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
        req.setConfirmAction(true);
        humanApprovalService.createApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), req, memberUser
        );

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertNotNull(summary);
        assertEquals("APPROVED", summary.codeChangeStatus());
        assertTrue(summary.isCodeChangeActive());
        assertTrue(summary.isAlreadyApproved());
        assertFalse(summary.eligibleForNewApproval());
        assertEquals("CodeChange has already been APPROVED", summary.eligibilityReason());
    }

    @Test
    @DisplayName("Slice 8 Engine 3: testRejectedCodeChangeExplicitSelection")
    void testRejectedCodeChangeExplicitSelection() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        changeA.setStatus("REJECTED");
        codeChangeRepository.save(changeA);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertNotNull(summary);
        assertEquals("REJECTED", summary.codeChangeStatus());
        assertFalse(summary.isCodeChangeActive());
        assertFalse(summary.eligibleForNewApproval());
    }

    @Test
    @DisplayName("Slice 8 Engine 4: testValidationFailedCodeChangeExplicitSelection")
    void testValidationFailedCodeChangeExplicitSelection() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        changeA.setStatus("VALIDATION_FAILED");
        codeChangeRepository.save(changeA);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertNotNull(summary);
        assertEquals("VALIDATION_FAILED", summary.codeChangeStatus());
        assertFalse(summary.isCodeChangeActive());
        assertFalse(summary.eligibleForNewApproval());
    }

    @Test
    @DisplayName("Slice 8 Engine 5: testOmittedCodeChangeSelectsLatestActive")
    void testOmittedCodeChangeSelectsLatestActive() {
        // Change 1: Older, active
        CodeChange change1 = changeA;
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), change1.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Change 2: Newer, active
        CodeChange change2 = new CodeChange();
        change2.setTask(taskA);
        change2.setPlan(planA);
        change2.setRepository(repoA);
        change2.setFilePath("src/NewService.java");
        change2.setOperation("CREATE");
        change2.setNewContent("public class NewService {}");
        change2.setStatus("GENERATED");
        change2 = codeChangeRepository.save(change2);

        CodeReviewResultDto r2 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), change2.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Omit codeChangeId -> should select change2 (latest active)
        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), null, memberUser
        );

        assertEquals(change2.getId(), summary.codeChangeId());
        assertEquals(r2.reviewId(), summary.reviewId());
    }

    @Test
    @DisplayName("Slice 8 Engine 6: testNoActiveCodeChange")
    void testNoActiveCodeChange() {
        changeA.setStatus("REJECTED");
        codeChangeRepository.save(changeA);

        ApiException ex = assertThrows(ApiException.class, () ->
                summaryReportService.generateReviewSummary(projectA.getId(), taskA.getId(), null, memberUser)
        );
        assertEquals("NO_ACTIVE_CODE_CHANGE", ex.getCode());
    }

    @Test
    @DisplayName("Slice 8 Engine 7: testEqualUpdatedAtUsesDeterministicIdTieBreak")
    void testEqualUpdatedAtUsesDeterministicIdTieBreak() {
        Instant sameTime = Instant.now();
        changeA.setUpdatedAt(sameTime);
        codeChangeRepository.save(changeA);
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange change2 = new CodeChange();
        change2.setTask(taskA);
        change2.setPlan(planA);
        change2.setRepository(repoA);
        change2.setFilePath("src/TieBreakService.java");
        change2.setOperation("CREATE");
        change2.setNewContent("public class TieBreakService {}");
        change2.setStatus("GENERATED");
        change2.setUpdatedAt(sameTime);
        change2 = codeChangeRepository.save(change2);

        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), change2.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Generate summary multiple times; tie break by id DESC must be 100% deterministic
        CodeReviewSummaryReportDto s1 = summaryReportService.generateReviewSummary(projectA.getId(), taskA.getId(), null, memberUser);
        CodeReviewSummaryReportDto s2 = summaryReportService.generateReviewSummary(projectA.getId(), taskA.getId(), null, memberUser);

        assertEquals(s1.codeChangeId(), s2.codeChangeId());
    }

    @Test
    @DisplayName("Slice 8 Engine 8: testReviewMustBelongToSelectedCodeChange")
    void testReviewMustBelongToSelectedCodeChange() {
        CodeChange change2 = new CodeChange();
        change2.setTask(taskA);
        change2.setPlan(planA);
        change2.setRepository(repoA);
        change2.setFilePath("src/UnreviewedService.java");
        change2.setOperation("CREATE");
        change2.setNewContent("public class UnreviewedService {}");
        change2.setStatus("GENERATED");
        final CodeChange change2Saved = codeChangeRepository.save(change2);

        // Request summary for change2 which has no completed review
        ApiException ex = assertThrows(ApiException.class, () ->
                summaryReportService.generateReviewSummary(projectA.getId(), taskA.getId(), change2Saved.getId(), memberUser)
        );
        assertEquals("REVIEW_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("Slice 8 Engine 9: testLatestCompletedReviewSelected")
    void testLatestCompletedReviewSelected() {
        CodeReviewResultDto r1 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );
        CodeReviewResultDto r2 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(r2.reviewId(), summary.reviewId(), "Latest completed review MUST be selected");
    }

    @Test
    @DisplayName("Slice 8 Engine 10: testStaleLatestReviewDoesNotFallbackToOlderCleanReview")
    void testStaleLatestReviewDoesNotFallbackToOlderCleanReview() {
        // Clean review 1
        CodeReviewResultDto r1 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Mutate changeA context
        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setProposedHash("mutated_context_hash_slice8");
        codeChangeRepository.save(cc);

        // Review 2 against mutated context
        CodeReviewResultDto r2 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), cc.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Mutate context again so review 2 context hash is now stale
        cc = codeChangeRepository.findById(cc.getId()).orElseThrow();
        cc.setProposedHash("mutated_context_hash_slice8_again");
        codeChangeRepository.save(cc);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), cc.getId(), memberUser
        );

        assertEquals(r2.reviewId(), summary.reviewId(), "MUST select latest review r2 despite staleness; MUST NOT fallback to r1");
        assertTrue(summary.isContextStale());
        assertFalse(summary.eligibleForApproval());
    }

    @Test
    @DisplayName("Slice 8 Engine 11: testEligibilityDelegatesToSlice7")
    void testEligibilityDelegatesToSlice7() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        // Inject CRITICAL finding
        ReviewFinding criticalFinding = new ReviewFinding();
        criticalFinding.setReview(review);
        criticalFinding.setProject(projectA);
        criticalFinding.setRepository(repoA);
        criticalFinding.setTask(taskA);
        criticalFinding.setCodeChange(changeA);
        criticalFinding.setFindingType(FindingType.AI_FINDING);
        criticalFinding.setCategory("SECURITY");
        criticalFinding.setCweId("CWE-89");
        criticalFinding.setSeverity(FindingSeverity.CRITICAL);
        criticalFinding.setConfidence(FindingConfidence.HIGH);
        criticalFinding.setTitle("SQL Injection");
        criticalFinding.setDescription("Critical SQL Injection");
        criticalFinding.setFilePath("src/SecurityGateService.java");
        criticalFinding.setStartLine(1);
        criticalFinding.setEndLine(10);
        criticalFinding.setContentHash("hash_crit_123");
        criticalFinding.setFingerprint("fp_crit_123");
        reviewFindingRepository.save(criticalFinding);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertFalse(summary.eligibleForApproval(), "Slice 7 gate MUST reject eligibility for CRITICAL finding");
        assertEquals(1, summary.findingsSummary().criticalCount());
    }

    @Test
    @DisplayName("Slice 8 Engine 12: testFindingSeverityAggregationDatabaseSide")
    void testFindingSeverityAggregationDatabaseSide() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        // Add 1 Critical, 2 High, 3 Medium, 4 Low, 5 Info findings
        createFinding(review, FindingSeverity.CRITICAL, "Critical finding");
        createFinding(review, FindingSeverity.HIGH, "High finding 1");
        createFinding(review, FindingSeverity.HIGH, "High finding 2");
        createFinding(review, FindingSeverity.MEDIUM, "Medium finding 1");
        createFinding(review, FindingSeverity.MEDIUM, "Medium finding 2");
        createFinding(review, FindingSeverity.MEDIUM, "Medium finding 3");
        createFinding(review, FindingSeverity.LOW, "Low finding 1");
        createFinding(review, FindingSeverity.LOW, "Low finding 2");
        createFinding(review, FindingSeverity.LOW, "Low finding 3");
        createFinding(review, FindingSeverity.LOW, "Low finding 4");
        createFinding(review, FindingSeverity.INFO, "Info finding 1");
        createFinding(review, FindingSeverity.INFO, "Info finding 2");
        createFinding(review, FindingSeverity.INFO, "Info finding 3");
        createFinding(review, FindingSeverity.INFO, "Info finding 4");
        createFinding(review, FindingSeverity.INFO, "Info finding 5");

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(15, summary.findingsSummary().totalCount());
        assertEquals(1, summary.findingsSummary().criticalCount());
        assertEquals(2, summary.findingsSummary().highCount());
        assertEquals(3, summary.findingsSummary().mediumCount());
        assertEquals(4, summary.findingsSummary().lowCount());
        assertEquals(5, summary.findingsSummary().infoCount());
    }

    @Test
    @DisplayName("Slice 8 Engine 13: testFindingSnippetLimitIs50")
    void testFindingSnippetLimitIs50() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        // Create 60 findings (>50 DB limit)
        for (int i = 0; i < 60; i++) {
            createFinding(review, FindingSeverity.INFO, "Info finding " + i);
        }

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(60, summary.findingsSummary().totalCount());
        assertEquals(50, summary.snippetCount(), "Snippet DTO count MUST be hard-bounded to 50");
        assertTrue(summary.snippetsCapped(), "snippetsCapped MUST be true when totalCount > 50");
    }

    @Test
    @DisplayName("Slice 8 Engine 14: testSnippetOrderingDeterministic")
    void testSnippetOrderingDeterministic() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        createFinding(review, FindingSeverity.INFO, "Info finding");
        createFinding(review, FindingSeverity.CRITICAL, "Critical finding");
        createFinding(review, FindingSeverity.HIGH, "High finding");

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(3, summary.snippets().size());
        assertEquals(FindingSeverity.CRITICAL, summary.snippets().get(0).severity());
        assertEquals(FindingSeverity.HIGH, summary.snippets().get(1).severity());
        assertEquals(FindingSeverity.INFO, summary.snippets().get(2).severity());
    }

    @Test
    @DisplayName("Slice 8 Engine 15: testSecretRedactionInFindingSnippets")
    void testSecretRedactionInFindingSnippets() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        ReviewFinding f = new ReviewFinding();
        f.setReview(review);
        f.setProject(projectA);
        f.setRepository(repoA);
        f.setTask(taskA);
        f.setCodeChange(changeA);
        f.setFindingType(FindingType.AI_FINDING);
        f.setCategory("SECRET_LEAK");
        f.setSeverity(FindingSeverity.MEDIUM);
        f.setConfidence(FindingConfidence.HIGH);
        f.setTitle("Found API key ghp_1234567890abcdef1234567890abcdef123456");
        f.setDescription("Leaked secret bearer token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        f.setFilePath("src/SecurityGateService.java");
        f.setStartLine(1);
        f.setEndLine(10);
        f.setContentHash("hash_secret_123");
        f.setFingerprint("fp_secret_123");
        reviewFindingRepository.save(f);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertFalse(summary.snippets().isEmpty());
        ReviewFindingSnippetDto snippet = summary.snippets().get(0);
        assertFalse(snippet.title().contains("ghp_1234567890abcdef1234567890abcdef123456"));
        assertFalse(snippet.description().contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        assertTrue(snippet.title().contains("[REDACTED_") || snippet.description().contains("[REDACTED_"));
    }

    @Test
    @DisplayName("Slice 8 Engine 16: testContextDriftIsObservationalOnly")
    void testContextDriftIsObservationalOnly() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setProposedHash("drifted_context_hash");
        codeChangeRepository.save(cc);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), cc.getId(), memberUser
        );

        assertTrue(summary.isContextStale());

        // Verify DB review status remains COMPLETED (observational only, no mutation of DB state!)
        CodeReview reviewInDb = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, reviewInDb.getStatus(), "Review status MUST remain COMPLETED in DB");
    }

    @Test
    @DisplayName("Slice 8 Engine 17: testApprovedCodeChangeCannotBecomeNewApprovalCandidate")
    void testApprovedCodeChangeCannotBecomeNewApprovalCandidate() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
        req.setConfirmAction(true);
        humanApprovalService.createApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), req, memberUser
        );

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertTrue(summary.isAlreadyApproved());
        assertFalse(summary.eligibleForNewApproval());

        // Attempting to re-approve MUST fail with ACTIVE_APPROVAL_EXISTS
        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), req, memberUser
                )
        );
        assertEquals("ACTIVE_APPROVAL_EXISTS", ex.getCode());
    }

    @Test
    @DisplayName("Slice 8 Engine 18: testRepositoryIdIsServerDerived")
    void testRepositoryIdIsServerDerived() {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(repoA.getId(), summary.repositoryId(), "repositoryId MUST be server-derived from task.getRepository()");

        // Cross-project mismatch attempt for Task B (belongs to Project B) queried under Project A
        ApiException ex = assertThrows(ApiException.class, () ->
                summaryReportService.generateReviewSummary(projectA.getId(), taskB.getId(), null, memberUser)
        );
        assertEquals("TASK_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("Slice 8 Engine 19: testUnchangedContextNotStale")
    void testUnchangedContextNotStale() {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertFalse(summary.isContextStale(), "Unchanged CodeChange MUST NOT be stale");
    }

    @Test
    @DisplayName("Slice 8 Engine 20: testFilePathMutationMakesContextStale")
    void testFilePathMutationMakesContextStale() {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setFilePath("src/MutatedSecurityGateService.java");
        codeChangeRepository.save(cc);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), cc.getId(), memberUser
        );

        assertTrue(summary.isContextStale(), "filePath mutation MUST make context stale");
    }

    @Test
    @DisplayName("Slice 8 Engine 21: testDiffMutationMakesContextStale")
    void testDiffMutationMakesContextStale() {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setDiff("--- src/SecurityGateService.java\n+++ src/SecurityGateService.java\n@@ -1 +1 @@\n-old\n+mutated diff content\n");
        codeChangeRepository.save(cc);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), cc.getId(), memberUser
        );

        assertTrue(summary.isContextStale(), "diff mutation MUST make context stale");
    }

    @Test
    @DisplayName("Slice 8 Engine 22: testOriginalHashMutationMakesContextStale")
    void testOriginalHashMutationMakesContextStale() {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setOriginalHash("mutated_original_hash_999");
        codeChangeRepository.save(cc);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), cc.getId(), memberUser
        );

        assertTrue(summary.isContextStale(), "originalHash mutation MUST make context stale");
    }

    @Test
    @DisplayName("Slice 8 Engine 23: testProposedHashMutationMakesContextStale")
    void testProposedHashMutationMakesContextStale() {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setProposedHash("mutated_proposed_hash_888");
        codeChangeRepository.save(cc);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), cc.getId(), memberUser
        );

        assertTrue(summary.isContextStale(), "proposedHash mutation MUST make context stale");
    }

    @Test
    @DisplayName("Slice 8 Engine 24: testCompletedAtOrderingInversion")
    void testCompletedAtOrderingInversion() {
        Instant base = Instant.now();

        // Review A: created earlier (10:00), completed later (10:30)
        CodeReview reviewA = new CodeReview();
        reviewA.setProject(projectA);
        reviewA.setRepository(repoA);
        reviewA.setTask(taskA);
        reviewA.setCodeChange(changeA);
        reviewA.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        reviewA.setStatus(ReviewStatus.COMPLETED);
        reviewA.setModelIdentifier("gemini-1.5-pro");
        reviewA.setCreatedAt(base.minusSeconds(1800)); // 10:00
        reviewA.setCompletedAt(base);                  // 10:30
        reviewA.setReviewContextHash("hashA");
        reviewA.setSummary("Review A summary");
        reviewA.setOverallRiskScore("LOW");
        reviewA = codeReviewRepository.save(reviewA);

        // Review B: created later (10:05), completed earlier (10:10)
        CodeReview reviewB = new CodeReview();
        reviewB.setProject(projectA);
        reviewB.setRepository(repoA);
        reviewB.setTask(taskA);
        reviewB.setCodeChange(changeA);
        reviewB.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        reviewB.setStatus(ReviewStatus.COMPLETED);
        reviewB.setModelIdentifier("gemini-1.5-pro");
        reviewB.setCreatedAt(base.minusSeconds(1500)); // 10:05
        reviewB.setCompletedAt(base.minusSeconds(1200)); // 10:10
        reviewB.setReviewContextHash("hashB");
        reviewB.setSummary("Review B summary");
        reviewB.setOverallRiskScore("LOW");
        reviewB = codeReviewRepository.save(reviewB);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(reviewA.getId(), summary.reviewId(), "Review A completed later (10:30 vs 10:10) MUST be selected over Review B");
    }

    @Test
    @DisplayName("Slice 8 Engine 25: testEqualCompletedAtBreaksTieByCreatedAt")
    void testEqualCompletedAtBreaksTieByCreatedAt() {
        Instant sameCompletedTime = Instant.now();

        // Review A: createdAt = 10:05
        CodeReview reviewA = new CodeReview();
        reviewA.setProject(projectA);
        reviewA.setRepository(repoA);
        reviewA.setTask(taskA);
        reviewA.setCodeChange(changeA);
        reviewA.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        reviewA.setStatus(ReviewStatus.COMPLETED);
        reviewA.setModelIdentifier("gemini-1.5-pro");
        reviewA.setCreatedAt(sameCompletedTime.minusSeconds(300));
        reviewA.setCompletedAt(sameCompletedTime);
        reviewA.setReviewContextHash("hashA");
        reviewA.setSummary("Review A summary");
        reviewA.setOverallRiskScore("LOW");
        reviewA = codeReviewRepository.save(reviewA);

        // Review B: createdAt = 10:00
        CodeReview reviewB = new CodeReview();
        reviewB.setProject(projectA);
        reviewB.setRepository(repoA);
        reviewB.setTask(taskA);
        reviewB.setCodeChange(changeA);
        reviewB.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        reviewB.setStatus(ReviewStatus.COMPLETED);
        reviewB.setModelIdentifier("gemini-1.5-pro");
        reviewB.setCreatedAt(sameCompletedTime.minusSeconds(600));
        reviewB.setCompletedAt(sameCompletedTime);
        reviewB.setReviewContextHash("hashB");
        reviewB.setSummary("Review B summary");
        reviewB.setOverallRiskScore("LOW");
        reviewB = codeReviewRepository.save(reviewB);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(reviewA.getId(), summary.reviewId(), "Equal completedAt MUST break tie by createdAt DESC (10:05 > 10:00)");
    }

    @Test
    @DisplayName("Slice 8 Engine 26: testEqualCompletedAtAndCreatedAtBreaksTieById")
    void testEqualCompletedAtAndCreatedAtBreaksTieById() {
        Instant sameTime = Instant.now();

        CodeReview review1 = new CodeReview();
        review1.setProject(projectA);
        review1.setRepository(repoA);
        review1.setTask(taskA);
        review1.setCodeChange(changeA);
        review1.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review1.setStatus(ReviewStatus.COMPLETED);
        review1.setModelIdentifier("gemini-1.5-pro");
        review1.setCreatedAt(sameTime);
        review1.setCompletedAt(sameTime);
        review1.setReviewContextHash("hash1");
        review1.setSummary("Review 1 summary");
        review1.setOverallRiskScore("LOW");
        review1 = codeReviewRepository.save(review1);

        CodeReview review2 = new CodeReview();
        review2.setProject(projectA);
        review2.setRepository(repoA);
        review2.setTask(taskA);
        review2.setCodeChange(changeA);
        review2.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review2.setStatus(ReviewStatus.COMPLETED);
        review2.setModelIdentifier("gemini-1.5-pro");
        review2.setCreatedAt(sameTime);
        review2.setCompletedAt(sameTime);
        review2.setReviewContextHash("hash2");
        review2.setSummary("Review 2 summary");
        review2.setOverallRiskScore("LOW");
        review2 = codeReviewRepository.save(review2);

        UUID expectedWinnerId = review1.getId().toString().compareTo(review2.getId().toString()) > 0 ? review1.getId() : review2.getId();

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(expectedWinnerId, summary.reviewId(), "Equal completedAt & createdAt MUST break tie by id DESC");
    }

    @Test
    @DisplayName("Slice 8 Engine 27: testIncompleteReviewsIgnored")
    void testIncompleteReviewsIgnored() {
        Instant now = Instant.now();

        // Completed review created earlier
        CodeReview completedReview = new CodeReview();
        completedReview.setProject(projectA);
        completedReview.setRepository(repoA);
        completedReview.setTask(taskA);
        completedReview.setCodeChange(changeA);
        completedReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        completedReview.setStatus(ReviewStatus.COMPLETED);
        completedReview.setModelIdentifier("gemini-1.5-pro");
        completedReview.setCreatedAt(now.minusSeconds(600));
        completedReview.setCompletedAt(now.minusSeconds(300));
        completedReview.setReviewContextHash("hash_completed");
        completedReview.setSummary("Completed review summary");
        completedReview.setOverallRiskScore("LOW");
        completedReview = codeReviewRepository.save(completedReview);

        // Pending review created later
        CodeReview pendingReview = new CodeReview();
        pendingReview.setProject(projectA);
        pendingReview.setRepository(repoA);
        pendingReview.setTask(taskA);
        pendingReview.setCodeChange(changeA);
        pendingReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        pendingReview.setStatus(ReviewStatus.PENDING);
        pendingReview.setModelIdentifier("gemini-1.5-pro");
        pendingReview.setReviewContextHash("hash_pending");
        pendingReview.setCreatedAt(now);
        pendingReview = codeReviewRepository.save(pendingReview);

        // Running review created later
        CodeReview runningReview = new CodeReview();
        runningReview.setProject(projectA);
        runningReview.setRepository(repoA);
        runningReview.setTask(taskA);
        runningReview.setCodeChange(changeA);
        runningReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        runningReview.setStatus(ReviewStatus.RUNNING);
        runningReview.setModelIdentifier("gemini-1.5-pro");
        runningReview.setReviewContextHash("hash_running");
        runningReview.setCreatedAt(now);
        runningReview = codeReviewRepository.save(runningReview);

        // Failed review created later
        CodeReview failedReview = new CodeReview();
        failedReview.setProject(projectA);
        failedReview.setRepository(repoA);
        failedReview.setTask(taskA);
        failedReview.setCodeChange(changeA);
        failedReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        failedReview.setStatus(ReviewStatus.FAILED);
        failedReview.setModelIdentifier("gemini-1.5-pro");
        failedReview.setReviewContextHash("hash_failed");
        failedReview.setCreatedAt(now);
        failedReview.setCompletedAt(now);
        failedReview = codeReviewRepository.save(failedReview);

        CodeReviewSummaryReportDto summary = summaryReportService.generateReviewSummary(
                projectA.getId(), taskA.getId(), changeA.getId(), memberUser
        );

        assertEquals(completedReview.getId(), summary.reviewId(), "Non-COMPLETED reviews (PENDING, RUNNING, FAILED) MUST be ignored");
    }

    private void createFinding(CodeReview review, FindingSeverity severity, String title) {
        ReviewFinding f = new ReviewFinding();
        f.setReview(review);
        f.setProject(projectA);
        f.setRepository(repoA);
        f.setTask(taskA);
        f.setCodeChange(changeA);
        f.setFindingType(FindingType.AI_FINDING);
        f.setCategory("TEST");
        f.setSeverity(severity);
        f.setConfidence(FindingConfidence.HIGH);
        f.setTitle(title);
        f.setDescription("Description for " + title);
        f.setFilePath("src/SecurityGateService.java");
        f.setStartLine(1);
        f.setEndLine(10);
        f.setContentHash("hash_" + UUID.randomUUID().toString().replace("-", ""));
        f.setFingerprint("fp_" + UUID.randomUUID().toString().replace("-", ""));
        reviewFindingRepository.save(f);
    }
}
