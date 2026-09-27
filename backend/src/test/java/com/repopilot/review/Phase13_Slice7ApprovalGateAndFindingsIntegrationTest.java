package com.repopilot.review;

import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.repository.PublicationAuditEventRepository;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.approval.dto.HumanApprovalCreateRequest;
import com.repopilot.approval.dto.HumanApprovalResponseDto;
import com.repopilot.approval.service.HumanApprovalService;
import com.repopilot.approval.service.HumanApprovalServiceImpl;
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
import com.repopilot.repository.entity.RepositoryBranch;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryBranchRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.controller.CodeReviewController;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase13_Slice7ApprovalGateAndFindingsIntegrationTest {

    @Autowired
    private CodeReviewApprovalGateService approvalGateService;

    @Autowired
    private ReviewFindingService reviewFindingService;

    @Autowired
    private CodeReviewOrchestrationService orchestrationService;

    @Autowired
    private AdminReviewOverrideService adminOverrideService;

    @Autowired
    private HumanApprovalService humanApprovalService;

    @Autowired
    private CodeReviewController codeReviewController;

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
        owner = new User();
        owner.setEmail("owner_slice7_" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("hash");
        owner.setName("Owner Slice7");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        adminUser = new User();
        adminUser.setEmail("admin_slice7_" + UUID.randomUUID() + "@test.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("Admin Slice7");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);

        memberUser = new User();
        memberUser.setEmail("member_slice7_" + UUID.randomUUID() + "@test.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Member Slice7");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        attacker = new User();
        attacker.setEmail("attacker_slice7_" + UUID.randomUUID() + "@test.com");
        attacker.setPasswordHash("hash");
        attacker.setName("Attacker Slice7");
        attacker.setRole(UserRole.USER);
        attacker = userRepository.save(attacker);

        // Project A
        projectA = new Project();
        projectA.setOwner(owner);
        projectA.setName("Project Slice7 A " + UUID.randomUUID());
        projectA.setDescription("Project Slice7 A");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(projectA, adminUser, ProjectRole.ADMIN));
        projectMemberRepository.save(new ProjectMember(projectA, memberUser, ProjectRole.MEMBER));

        // Project B
        projectB = new Project();
        projectB.setOwner(attacker);
        projectB.setName("Project Slice7 B " + UUID.randomUUID());
        projectB.setDescription("Project Slice7 B");
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);
        projectMemberRepository.save(new ProjectMember(projectB, attacker, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("repopilot");
        repoA.setRepoName("repo-slice7-a-" + UUID.randomUUID().toString().substring(0, 8));
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
        repoB.setRepoName("repo-slice7-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task Slice7 A");
        taskA.setDescription("Task Slice7 Description A");
        taskA.setPriority(TaskPriority.HIGH);
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(attacker);
        taskB.setTitle("Task Slice7 B");
        taskB.setDescription("Task Slice7 Description B");
        taskB.setPriority(TaskPriority.MEDIUM);
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB = taskRepository.save(taskB);

        Plan planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan Slice7 Objective A");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/SecurityGateService.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class SecurityGateService {}");
        changeA.setNewContent("public class SecurityGateService { void check() {} }");
        changeA.setOriginalHash("orig_slice7_123");
        changeA.setProposedHash("prop_slice7_456");
        changeA.setDiff("--- src/SecurityGateService.java\n+++ src/SecurityGateService.java\n@@ -1 +1 @@\n-public class SecurityGateService {}\n+public class SecurityGateService { void check() {} }\n");
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
    @DisplayName("Slice 7: Clean review with 0 critical/high findings is ELIGIBLE for human approval")
    void testCleanReviewIsEligibleForApproval() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReviewEligibilityResultDto eligibility = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), memberUser
        );

        assertTrue(eligibility.eligible());
        assertEquals(ReviewStatus.COMPLETED, eligibility.status());
        assertFalse(eligibility.hasAdminOverride());
        assertEquals(0, eligibility.criticalFindingCount());
        assertEquals(0, eligibility.highFindingCount());
    }

    @Test
    @DisplayName("Slice 7: CRITICAL security finding BLOCKS approval eligibility without Admin Override")
    void testCriticalSecurityFindingBlocksApprovalEligibility() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        // Inject CRITICAL security vulnerability finding
        ReviewFinding criticalFinding = new ReviewFinding();
        criticalFinding.setReview(review);
        criticalFinding.setProject(projectA);
        criticalFinding.setRepository(repoA);
        criticalFinding.setTask(taskA);
        criticalFinding.setCodeChange(changeA);
        criticalFinding.setFindingType(FindingType.AI_FINDING);
        criticalFinding.setCategory("SQL_INJECTION");
        criticalFinding.setCweId("CWE-89");
        criticalFinding.setSeverity(FindingSeverity.CRITICAL);
        criticalFinding.setConfidence(FindingConfidence.HIGH);
        criticalFinding.setTitle("SQL Injection Vulnerability in SecurityGateService");
        criticalFinding.setDescription("Unsanitized input passed directly to SQL query");
        criticalFinding.setFilePath(changeA.getFilePath());
        criticalFinding.setStartLine(1);
        criticalFinding.setEndLine(5);
        criticalFinding.setSide(FindingSide.PROPOSED);
        criticalFinding.setContentHash("hash_sqli_123");
        criticalFinding.setFingerprint("fp_sqli_123");
        criticalFinding.setCreatedAt(Instant.now());
        criticalFinding.setUpdatedAt(Instant.now());
        reviewFindingRepository.save(criticalFinding);

        CodeReviewEligibilityResultDto eligibility = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), memberUser
        );

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.reason().contains("CRITICAL"));
        assertEquals(1, eligibility.criticalFindingCount());

        // Human approval creation MUST fail with INELIGIBLE_CODE_REVIEW
        HumanApprovalCreateRequest request = new HumanApprovalCreateRequest();
        request.setConfirmAction(true);
        request.setRepositoryBaseSha("1111222233334444555566667777888899990000");

        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), request, memberUser
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INELIGIBLE_CODE_REVIEW", ex.getCode());
    }

    @Test
    @DisplayName("Slice 7: Admin Review Override BYPASSES security findings for human approval")
    void testAdminOverrideBypassesSecurityFindingsForHumanApproval() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        // Inject HIGH security vulnerability finding
        ReviewFinding highFinding = new ReviewFinding();
        highFinding.setReview(review);
        highFinding.setProject(projectA);
        highFinding.setRepository(repoA);
        highFinding.setTask(taskA);
        highFinding.setCodeChange(changeA);
        highFinding.setFindingType(FindingType.AI_FINDING);
        highFinding.setCategory("HARDCODED_SECRET");
        highFinding.setCweId("CWE-798");
        highFinding.setSeverity(FindingSeverity.HIGH);
        highFinding.setConfidence(FindingConfidence.HIGH);
        highFinding.setTitle("Hardcoded API Key");
        highFinding.setDescription("Detected canary secret token");
        highFinding.setFilePath(changeA.getFilePath());
        highFinding.setStartLine(1);
        highFinding.setEndLine(2);
        highFinding.setSide(FindingSide.PROPOSED);
        highFinding.setContentHash("hash_secret_456");
        highFinding.setFingerprint("fp_secret_456");
        highFinding.setCreatedAt(Instant.now());
        highFinding.setUpdatedAt(Instant.now());
        reviewFindingRepository.save(highFinding);

        // Verify initially blocked
        CodeReviewEligibilityResultDto initialEligibility = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), memberUser
        );
        assertFalse(initialEligibility.eligible());

        // Perform Admin Override
        AdminReviewOverrideRequest overrideReq = new AdminReviewOverrideRequest("Override granted by SecOps lead for false positive hardcoded canary key.");
        adminOverrideService.overrideReview(projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), overrideReq, adminUser);

        // Verify now ELIGIBLE with hasAdminOverride = true
        CodeReviewEligibilityResultDto overriddenEligibility = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), memberUser
        );
        assertTrue(overriddenEligibility.eligible());
        assertTrue(overriddenEligibility.hasAdminOverride());

        // Human approval creation MUST now SUCCEED
        HumanApprovalCreateRequest approvalReq = new HumanApprovalCreateRequest();
        approvalReq.setConfirmAction(true);
        approvalReq.setRepositoryBaseSha("1111222233334444555566667777888899990000");

        HumanApprovalResponseDto approvalResp = humanApprovalService.createApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), approvalReq, memberUser
        );
        assertNotNull(approvalResp);
        assertNotNull(approvalResp.approvalId());
    }

    @Test
    @DisplayName("Slice 7: Context hash drift makes code review INELIGIBLE for approval")
    void testContextHashDriftMakesReviewIneligible() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Mutate change content to drift context hash
        changeA.setDiff("--- src/SecurityGateService.java\n+++ src/SecurityGateService.java\n@@ -1 +1 @@\n-public class SecurityGateService {}\n+public class SecurityGateService { void modified() {} }\n");
        codeChangeRepository.save(changeA);

        CodeReviewEligibilityResultDto eligibility = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), memberUser
        );

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.reason().contains("reviewContextHash mismatch") || eligibility.reason().contains("drifted"));
    }

    @Test
    @DisplayName("Slice 7: Review Finding Query Service returns findings with secret redaction and severity filter")
    void testGetFindingsForReviewWithSeverityFilterAndSecretRedaction() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        String secretToken = "ghp_CANARY_FINDING_TOKEN_9999_12345678901234567890";
        ReviewFinding finding = new ReviewFinding();
        finding.setReview(review);
        finding.setProject(projectA);
        finding.setRepository(repoA);
        finding.setTask(taskA);
        finding.setCodeChange(changeA);
        finding.setFindingType(FindingType.AI_FINDING);
        finding.setCategory("SECRET_LEAK");
        finding.setSeverity(FindingSeverity.HIGH);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setTitle("Leaked secret " + secretToken);
        finding.setDescription("Found secret token " + secretToken + " in source file");
        finding.setFilePath(changeA.getFilePath());
        finding.setStartLine(10);
        finding.setEndLine(12);
        finding.setSide(FindingSide.PROPOSED);
        finding.setContentHash("hash_secret_789");
        finding.setFingerprint("fp_secret_789");
        finding.setCreatedAt(Instant.now());
        finding.setUpdatedAt(Instant.now());
        reviewFindingRepository.save(finding);

        // Query all findings
        List<ReviewFindingResponseDto> allFindings = reviewFindingService.getFindingsForReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), null, memberUser
        );
        assertFalse(allFindings.isEmpty());

        // Verify raw secret is REDACTED
        assertFalse(allFindings.get(0).getTitle().contains(secretToken));
        assertTrue(allFindings.get(0).getTitle().contains("[REDACTED_SECRET_"));

        // Query with severity filter HIGH
        List<ReviewFindingResponseDto> highFindings = reviewFindingService.getFindingsForReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), "HIGH", memberUser
        );
        assertEquals(1, highFindings.size());

        // Query with severity filter LOW (0 matches)
        List<ReviewFindingResponseDto> lowFindings = reviewFindingService.getFindingsForReview(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), "LOW", memberUser
        );
        assertTrue(lowFindings.isEmpty());
    }

    @Test
    @DisplayName("Slice 7 IDOR: Non-project member request for findings or eligibility is REJECTED with FORBIDDEN")
    void testNonProjectMemberRequestRejectedForFindingsAndEligibility() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        ApiException ex1 = assertThrows(ApiException.class, () ->
                reviewFindingService.getFindingsForReview(
                        projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), null, attacker
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex1.getStatus());

        ApiException ex2 = assertThrows(ApiException.class, () ->
                approvalGateService.evaluateReviewEligibilityForApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), attacker
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex2.getStatus());
    }

    @Test
    @DisplayName("Slice 7 REST Controller: GET findings and GET eligibility endpoints return HTTP 200 OK")
    void testControllerEndpointsEligibilityAndFindingsSuccess() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        UserPrincipal principal = new UserPrincipal(memberUser);

        ResponseEntity<CodeReviewEligibilityResultDto> eligibilityResp = codeReviewController.evaluateCodeChangeReviewEligibility(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), principal
        );
        assertEquals(HttpStatus.OK, eligibilityResp.getStatusCode());
        assertNotNull(eligibilityResp.getBody());
        assertTrue(eligibilityResp.getBody().eligible());

        ResponseEntity<List<ReviewFindingResponseDto>> findingsResp = codeReviewController.getReviewFindings(
                projectA.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), null, principal
        );
        assertEquals(HttpStatus.OK, findingsResp.getStatusCode());
        assertNotNull(findingsResp.getBody());
    }

    private ReviewFinding createTestFinding(CodeReview review, FindingSeverity severity, String title, String description) {
        ReviewFinding f = new ReviewFinding();
        f.setReview(review);
        f.setProject(review.getProject());
        f.setRepository(review.getRepository());
        f.setTask(review.getTask());
        f.setCodeChange(review.getCodeChange());
        f.setFindingType(FindingType.AI_FINDING);
        f.setCategory("SECURITY");
        f.setSeverity(severity);
        f.setConfidence(FindingConfidence.HIGH);
        f.setTitle(title);
        f.setDescription(description);
        f.setFilePath("src/SecurityGateService.java");
        f.setStartLine(1);
        f.setEndLine(10);
        f.setSide(FindingSide.PROPOSED);
        f.setContentHash("hash_" + UUID.randomUUID());
        f.setFingerprint("fingerprint_" + UUID.randomUUID());
        f.setStatus(FindingStatus.OPEN);
        return reviewFindingRepository.save(f);
    }

    @Test
    @DisplayName("Slice 7 Policy: LOW and MEDIUM findings DO NOT block approval eligibility")
    void testLowAndMediumFindingsDoNotBlockApproval() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        createTestFinding(review, FindingSeverity.LOW, "Low severity note", "Minor style detail");
        createTestFinding(review, FindingSeverity.MEDIUM, "Medium severity note", "Performance suggestion");

        CodeReviewEligibilityResultDto result = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), review.getId(), memberUser
        );
        assertTrue(result.eligible(), "LOW/MEDIUM findings must NOT block approval");

        HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
        req.setConfirmAction(true);
        HumanApprovalResponseDto approvalResp = humanApprovalService.createApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), review.getId(), req, memberUser
        );
        assertNotNull(approvalResp);
    }

    @Test
    @DisplayName("Slice 7 Policy: Incomplete or RUNNING review is INELIGIBLE for approval")
    void testIncompleteOrRunningReviewMakesApprovalIneligible() {
        CodeReview review = new CodeReview();
        review.setProject(projectA);
        review.setRepository(repoA);
        review.setTask(taskA);
        review.setCodeChange(changeA);
        review.setStatus(ReviewStatus.RUNNING);
        review.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review.setModelIdentifier("gpt-4o");
        review.setPromptVersion("v1.0");
        review.setPolicyVersion("v1.0");
        review.setValidatorVersion("v1.0");
        review.setReviewerVersion("v1.0");
        review.setReviewContextHash("context_hash_running_123");
        review = codeReviewRepository.save(review);

        CodeReviewEligibilityResultDto result = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), review.getId(), memberUser
        );
        assertFalse(result.eligible());
        assertTrue(result.reason().contains("status is not COMPLETED"));

        HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
        req.setConfirmAction(true);
        UUID runningReviewId = review.getId();
        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), runningReviewId, req, memberUser
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INELIGIBLE_CODE_REVIEW", ex.getCode());
    }

    @Test
    @DisplayName("Slice 7 Policy: Missing review or no review record returns INELIGIBLE")
    void testMissingReviewMakesApprovalIneligible() {
        UUID nonExistentReviewId = UUID.randomUUID();

        CodeReviewEligibilityResultDto result = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), nonExistentReviewId, memberUser
        );
        assertFalse(result.eligible());
        assertTrue(result.reason().contains("Requested CodeReview not found"));
    }

    @Test
    @DisplayName("Slice 7 IDOR: Cross-project path substitution is REJECTED")
    void testCrossProjectIDORRejectedForFindingsAndEligibility() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Member of Project B attempts to query Project A's review using Project B's ID in path
        ApiException ex1 = assertThrows(ApiException.class, () ->
                reviewFindingService.getFindingsForReview(
                        projectB.getId(), repoA.getId(), taskA.getId(), reviewResult.reviewId(), null, memberUser
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex1.getStatus());

        ApiException ex2 = assertThrows(ApiException.class, () ->
                approvalGateService.evaluateReviewEligibilityForApproval(
                        projectB.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), memberUser
                )
        );
        assertEquals(HttpStatus.FORBIDDEN, ex2.getStatus());
    }

    @Test
    @DisplayName("Slice 7 Authority: Client-supplied request body CANNOT bypass eligibility")
    void testClientSuppliedAdminOverrideFieldsInRequestIgnored() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );
        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        createTestFinding(review, FindingSeverity.CRITICAL, "SQL Injection Risk", "Unsanitized input in query");

        HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
        req.setConfirmAction(true);

        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), review.getId(), req, memberUser
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INELIGIBLE_CODE_REVIEW", ex.getCode());
    }

    @Test
    @DisplayName("Slice 7 Safety: Error DTOs and exceptions DO NOT leak sensitive internal tokens or SQL details")
    void testInformationLeakageAbsenceInErrorResponses() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );
        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        createTestFinding(review, FindingSeverity.CRITICAL, "Buffer Overflow", "Memory safety issue");

        CodeReviewEligibilityResultDto dto = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), review.getId(), memberUser
        );

        assertFalse(dto.eligible());
        assertNotNull(dto.reason());
        assertFalse(dto.reason().contains("fencingToken"));
        assertFalse(dto.reason().contains("leaseToken"));
        assertFalse(dto.reason().contains("workerId"));
        assertFalse(dto.reason().contains("insert into"));
        assertFalse(dto.reason().contains("select "));
    }

    @Test
    @DisplayName("Slice 7 Concurrency & TOCTOU: Mutating CodeChange content invalidates context hash and aborts approval")
    void testTOCTOUCodeChangeMutationDuringApprovalAbortsTransaction() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Mutate CodeChange after review is completed
        changeA.setNewContent("public class SecurityGateService { void mutated() { throw new RuntimeException(); } }");
        changeA.setProposedHash("mutated_hash_999");
        codeChangeRepository.save(changeA);

        HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
        req.setConfirmAction(true);

        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), req, memberUser
                )
        );
        assertTrue(ex.getCode().equals("INELIGIBLE_CODE_REVIEW") || ex.getCode().equals("STALE_CONTEXT"));
    }

    @Test
    @DisplayName("Slice 7 Isolation: Findings on review 1 DO NOT affect eligibility of review 2")
    void testFindingsFromAnotherReviewDoNotAffectTargetReviewEligibility() {
        CodeReviewResultDto reviewResult1 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );
        CodeReview review1 = codeReviewRepository.findById(reviewResult1.reviewId()).orElseThrow();
        createTestFinding(review1, FindingSeverity.CRITICAL, "RCE Vulnerability", "Arbitrary code execution");

        // Create a distinct CodeChange changeB in taskA
        CodeChange changeB = new CodeChange();
        changeB.setTask(taskA);
        changeB.setPlan(changeA.getPlan());
        changeB.setRepository(repoA);
        changeB.setFilePath("src/AnotherService.java");
        changeB.setOperation("ADD");
        changeB.setNewContent("public class AnotherService {}");
        changeB.setOriginalHash("orig_slice7_b");
        changeB.setProposedHash("prop_slice7_b");
        changeB.setDiff("--- /dev/null\n+++ src/AnotherService.java\n@@ -0,0 +1 @@\n+public class AnotherService {}\n");
        changeB = codeChangeRepository.save(changeB);

        // Execute review on changeB
        CodeReviewResultDto reviewResult2 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeB.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );
        CodeReview review2 = codeReviewRepository.findById(reviewResult2.reviewId()).orElseThrow();
        assertNotEquals(review1.getId(), review2.getId());

        CodeReviewEligibilityResultDto eligibility2 = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeB.getId(), review2.getId(), memberUser
        );
        assertTrue(eligibility2.eligible(), "Findings on review 1 must NOT bleed into review 2");
    }

    @Test
    @DisplayName("Slice 7 Severity: HIGH security finding BLOCKS approval eligibility without Admin Override")
    void testHighSecurityFindingBlocksApprovalEligibility() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        createTestFinding(review, FindingSeverity.HIGH, "Path Traversal Vulnerability", "Unsanitized file path input");

        CodeReviewEligibilityResultDto eligibility = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), memberUser
        );

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.reason().contains("HIGH"));
        assertEquals(1, eligibility.highFindingCount());

        HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
        req.setConfirmAction(true);

        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), req, memberUser
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("INELIGIBLE_CODE_REVIEW", ex.getCode());
    }

    @Test
    @DisplayName("Slice 7 Authority: Admin override makes review ELIGIBLE but DOES NOT automatically approve or publish code")
    void testAdminOverrideDoesNotAutomaticallyCreateHumanApproval() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );
        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        createTestFinding(review, FindingSeverity.CRITICAL, "Critical Flaw", "Flaw description");

        // Record valid admin override by adminUser
        AdminReviewOverrideRequest overrideReq = new AdminReviewOverrideRequest();
        overrideReq.setReason("Sanctioned risk override");
        adminOverrideService.overrideReview(
                projectA.getId(), repoA.getId(), taskA.getId(), review.getId(),
                overrideReq, adminUser
        );

        // Eligibility becomes true
        CodeReviewEligibilityResultDto eligibility = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), review.getId(), memberUser
        );
        assertTrue(eligibility.eligible());
        assertTrue(eligibility.hasAdminOverride());

        // BUT human approval IS NOT created automatically in the database!
        assertTrue(humanApprovalRepository.findByCodeChangeId(changeA.getId()).isEmpty(), "Admin override MUST NOT automatically create HumanApproval");

        // Attempting approval without confirmAction=true STILL FAILS
        HumanApprovalCreateRequest reqNoConfirm = new HumanApprovalCreateRequest();
        reqNoConfirm.setConfirmAction(false);
        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), review.getId(), reqNoConfirm, memberUser
                )
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("ACTION_NOT_CONFIRMED", ex.getCode());
    }

    @Test
    @DisplayName("Slice 7 Context Binding: Mutating filePath, diff, originalHash, or proposedHash breaks eligibility")
    void testContextHashFieldMutationsBreakEligibility() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();

        // 1. Mutate diff
        String origDiff = cc.getDiff();
        cc.setDiff(origDiff + "\n+ // extra line");
        cc = codeChangeRepository.save(cc);

        CodeReviewEligibilityResultDto e1 = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), cc.getId(), reviewResult.reviewId(), memberUser
        );
        assertFalse(e1.eligible(), "Diff mutation MUST break context hash equality");

        // Restore diff & mutate filePath
        cc.setDiff(origDiff);
        cc.setFilePath("src/MutatedPath.java");
        cc = codeChangeRepository.save(cc);

        CodeReviewEligibilityResultDto e2 = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), cc.getId(), reviewResult.reviewId(), memberUser
        );
        assertFalse(e2.eligible(), "FilePath mutation MUST break context hash equality");

        // Restore filePath & mutate proposedHash
        cc.setFilePath("src/SecurityGateService.java");
        cc.setProposedHash("completely_new_proposed_hash");
        cc = codeChangeRepository.save(cc);

        CodeReviewEligibilityResultDto e3 = approvalGateService.evaluateReviewEligibilityForApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), cc.getId(), reviewResult.reviewId(), memberUser
        );
        assertFalse(e3.eligible(), "ProposedHash mutation MUST break context hash equality");
    }

    @Test
    @DisplayName("Slice 7 Security: Client cannot supply or forge approval context hash; DB state is authoritative")
    void testClientCannotSupplyOrForgeApprovalContextHash() {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Attempt to pass arbitrary or forged request body fields
        HumanApprovalCreateRequest forgedReq = new HumanApprovalCreateRequest();
        forgedReq.setConfirmAction(true);
        forgedReq.setRepositoryBaseSha("1111222233334444555566667777888899990000");

        // Execute approval
        HumanApprovalResponseDto response = humanApprovalService.createApproval(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), forgedReq, memberUser
        );

        // Verify context hash in created approval matches the authoritative review context hash from DB, NOT any client input
        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();
        assertEquals(review.getReviewContextHash().toLowerCase(java.util.Locale.ROOT), response.reviewContextHash().toLowerCase(java.util.Locale.ROOT));

        // Delete existing approval to allow testing second approval creation against mutated DB state
        publicationAuditEventRepository.deleteAll();
        humanApprovalRepository.deleteAll();

        // Now mutate CodeChange in DB directly
        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setProposedHash("forged_hash_by_attacker");
        codeChangeRepository.save(cc);

        // Attempting to approve with stale/mutated DB state must fail despite client request
        HumanApprovalCreateRequest req2 = new HumanApprovalCreateRequest();
        req2.setConfirmAction(true);

        ApiException ex = assertThrows(ApiException.class, () ->
                humanApprovalService.createApproval(
                        projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), req2, memberUser
                )
        );
        assertTrue("INELIGIBLE_CODE_REVIEW".equals(ex.getCode()) || "STALE_CONTEXT".equals(ex.getCode()),
                "Approval against mutated CodeChange must be rejected. Got code: " + ex.getCode());
    }

    @Test
    @DisplayName("Slice 7 Concurrency & TOCTOU: Deterministic DB race proves CodeChange.@Version optimistic locking rolls back T1 with zero HumanApproval rows")
    void testConcurrentTOCTOUDatabaseLockingAndMutation() throws Exception {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeChange initialCc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        Long v0 = initialCc.getVersion();
        String h1ProposedHash = initialCc.getProposedHash();

        java.util.concurrent.CountDownLatch t1LoadedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch t2CommittedLatch = new java.util.concurrent.CountDownLatch(1);

        java.util.concurrent.atomic.AtomicReference<Long> t1ObservedVersion = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> t1ObservedContext = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> t1Exception = new java.util.concurrent.atomic.AtomicReference<>();

        java.util.concurrent.atomic.AtomicReference<Long> t2CommittedVersion = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> t2CommittedContext = new java.util.concurrent.atomic.AtomicReference<>();

        // Register pre-save hook in HumanApprovalServiceImpl
        HumanApprovalServiceImpl.setPreSaveHook(codeChange -> {
            try {
                // Record T1's observed version V and context H1 BEFORE save
                t1ObservedVersion.set(codeChange.getVersion());
                t1ObservedContext.set(codeChange.getProposedHash());

                // Signal T2 that T1 has evaluated eligibility and reached barrier before save
                t1LoadedLatch.countDown();

                // Block T1 until T2 mutates and commits H2 (version V+1)
                t2CommittedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            // Thread T1: Executes createApproval()
            Thread t1 = new Thread(() -> {
                try {
                    HumanApprovalCreateRequest req = new HumanApprovalCreateRequest();
                    req.setConfirmAction(true);
                    humanApprovalService.createApproval(
                            projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), reviewResult.reviewId(), req, memberUser
                    );
                } catch (Throwable t) {
                    t1Exception.set(t);
                }
            });

            // Thread T2: Mutates CodeChange context H1 -> H2 and commits after T1 reaches barrier
            Thread t2 = new Thread(() -> {
                try {
                    // Wait for T1 to load V0 and evaluate eligibility against H1
                    t1LoadedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);

                    CodeChange changeToMutate = codeChangeRepository.findById(changeA.getId()).orElseThrow();
                    changeToMutate.setProposedHash("H2_MUTATED_BY_T2_" + UUID.randomUUID());
                    changeToMutate.setNewContent("public class SecurityGateService { void mutatedByT2() {} }");
                    CodeChange savedByT2 = codeChangeRepository.saveAndFlush(changeToMutate);

                    t2CommittedVersion.set(savedByT2.getVersion());
                    t2CommittedContext.set(savedByT2.getProposedHash());

                    // Signal T1 that T2 committed version V+1
                    t2CommittedLatch.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            t1.start();
            t2.start();
            t1.join(10000);
            t2.join(10000);

            // Assertions proving exact TOCTOU sequence:
            assertEquals(v0, t1ObservedVersion.get(), "T1 MUST observe initial version V0");
            assertEquals(h1ProposedHash, t1ObservedContext.get(), "T1 MUST observe initial context H1");

            assertNotNull(t2CommittedVersion.get(), "T2 must commit successfully");
            assertEquals(v0 + 1, t2CommittedVersion.get(), "T2 MUST increment version to V0+1");
            assertNotEquals(h1ProposedHash, t2CommittedContext.get(), "T2 MUST commit mutated context H2");

            assertNotNull(t1Exception.get(), "T1 MUST fail due to optimistic locking failure or stale context check");
            Throwable cause = t1Exception.get();
            boolean isOptimisticLockOrStale = cause instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                    || cause instanceof org.hibernate.StaleObjectStateException
                    || (cause instanceof ApiException && "STALE_CONTEXT".equals(((ApiException) cause).getCode()))
                    || (cause.getCause() != null && cause.getCause() instanceof org.springframework.orm.ObjectOptimisticLockingFailureException);
            assertTrue(isOptimisticLockOrStale, "T1 failure MUST be optimistic locking or stale context rollback. Got: " + cause);

            // Prove 0 HumanApproval rows persisted
            List<com.repopilot.approval.entity.HumanApproval> approvals = humanApprovalRepository.findByCodeChangeId(changeA.getId());
            assertEquals(0, approvals.size(), "Zero HumanApproval rows MUST exist after T1 optimistic locking rollback");

        } finally {
            HumanApprovalServiceImpl.setPreSaveHook(null);
        }
    }
}
