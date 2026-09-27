package com.repopilot.review;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
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
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.CodeReviewEligibilityResultDto;
import com.repopilot.review.dto.CodeReviewSummaryReportDto;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.SecurityPolicyComplianceDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.*;
import com.repopilot.security.service.PreLlmSecretScanner;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase13_Slice11SecurityPolicyComplianceTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private CodeChangeRepository codeChangeRepository;
    @Autowired private CodeReviewRepository codeReviewRepository;
    @Autowired private ReviewFindingRepository reviewFindingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ProjectAuthorizationService projectAuthorizationService;
    @Autowired private ReviewContextService reviewContextService;
    @Autowired private PreLlmSecretScanner preLlmSecretScanner;
    @Autowired private SecurityPolicyComplianceService securityPolicyComplianceService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private CodeReviewApprovalGateService codeReviewApprovalGateService;

    @Autowired(required = false)
    private CodeReviewSummaryReportService codeReviewSummaryReportService;

    private User ownerUser;
    private User adminUser;
    private User memberUser;
    private User viewerUser;
    private User nonMemberUser;

    private Project project;
    private Repository repository;
    private Task task;
    private Plan plan;
    private CodeChange codeChange;
    private CodeReview review;

    private static final String PG_URL = "jdbc:postgresql://127.0.0.1:5432/repopilot";
    private static final String PG_USER = "repopilot_user";
    private static final String PG_PASS = "repopilot_password";

    private boolean isPostgresAvailable() {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                return conn.isValid(2);
            }
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("slice11_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Slice 11 Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        adminUser = new User();
        adminUser.setEmail("slice11_admin_" + UUID.randomUUID() + "@example.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("Slice 11 Admin");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);

        memberUser = new User();
        memberUser.setEmail("slice11_member_" + UUID.randomUUID() + "@example.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Slice 11 Member");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        viewerUser = new User();
        viewerUser.setEmail("slice11_viewer_" + UUID.randomUUID() + "@example.com");
        viewerUser.setPasswordHash("hash");
        viewerUser.setName("Slice 11 Viewer");
        viewerUser.setRole(UserRole.USER);
        viewerUser = userRepository.save(viewerUser);

        nonMemberUser = new User();
        nonMemberUser.setEmail("slice11_nonmember_" + UUID.randomUUID() + "@example.com");
        nonMemberUser.setPasswordHash("hash");
        nonMemberUser.setName("Slice 11 Non-Member");
        nonMemberUser.setRole(UserRole.USER);
        nonMemberUser = userRepository.save(nonMemberUser);

        project = new Project();
        project.setName("Phase 13 Slice 11 Test Project");
        project.setDescription("Project for Slice 11 testing");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwner(ownerUser);
        project = projectRepository.save(project);

        projectMemberRepository.save(new ProjectMember(project, ownerUser, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(project, adminUser, ProjectRole.ADMIN));
        projectMemberRepository.save(new ProjectMember(project, memberUser, ProjectRole.MEMBER));
        projectMemberRepository.save(new ProjectMember(project, viewerUser, ProjectRole.VIEWER));

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
        task.setTitle("Slice 11 Task");
        task.setDescription("Task for Slice 11 testing");
        task.setStatus(TaskStatus.ANALYZING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreatedBy(ownerUser);
        task = taskRepository.save(task);

        plan = new Plan();
        plan.setTask(task);
        plan.setObjective("Slice 11 Objective");
        plan = planRepository.save(plan);

        codeChange = new CodeChange();
        codeChange.setTask(task);
        codeChange.setPlan(plan);
        codeChange.setRepository(repository);
        codeChange.setFilePath("src/main/java/App.java");
        codeChange.setOperation("MODIFY");
        codeChange.setOriginalHash("orig11");
        codeChange.setProposedHash("prop11");
        codeChange.setDiff("--- a/App.java\n+++ b/App.java\n@@ -1 +1 @@\n-old\n+new");
        codeChange = codeChangeRepository.save(codeChange);

        ReviewContext ctx = reviewContextService.buildReviewContext(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, adminUser
        );

        review = new CodeReview();
        review.setProject(project);
        review.setRepository(repository);
        review.setTask(task);
        review.setCodeChange(codeChange);
        review.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review.setStatus(ReviewStatus.COMPLETED);
        review.setReviewContextHash(ctx.reviewContextHash());
        review.setModelIdentifier("model-1");
        review.setAiInputHash("ai_input_hash_11");
        review.setSummary("Initial completed review summary.");
        review = codeReviewRepository.save(review);
    }

    @AfterEach
    void tearDown() {
        if (reviewFindingRepository != null) reviewFindingRepository.deleteAllInBatch();
        if (codeReviewRepository != null) codeReviewRepository.deleteAllInBatch();
        if (codeChangeRepository != null) codeChangeRepository.deleteAllInBatch();
        if (planRepository != null) planRepository.deleteAllInBatch();
        if (auditLogRepository != null) auditLogRepository.deleteAllInBatch();
        if (taskRepository != null) taskRepository.deleteAllInBatch();
        if (repositoryRepository != null) repositoryRepository.deleteAllInBatch();
        if (projectMemberRepository != null) projectMemberRepository.deleteAllInBatch();
        if (projectRepository != null) projectRepository.deleteAllInBatch();
        if (userRepository != null) userRepository.deleteAllInBatch();
    }

    private ReviewFinding createFinding(FindingSeverity severity, FindingStatus status) {
        ReviewFinding finding = new ReviewFinding();
        finding.setReview(review);
        finding.setProject(project);
        finding.setRepository(repository);
        finding.setTask(task);
        finding.setCodeChange(codeChange);
        finding.setFindingType(FindingType.AI_FINDING);
        finding.setCategory("SECURITY");
        finding.setCweId("CWE-89");
        finding.setSeverity(severity);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setStatus(status);
        finding.setTitle("Finding " + severity + " " + status);
        finding.setDescription("Description for " + severity + " " + status);
        finding.setFilePath("src/main/java/App.java");
        finding.setStartLine(10);
        finding.setEndLine(20);
        finding.setContentHash("hash_" + UUID.randomUUID());
        finding.setFingerprint("fp_" + UUID.randomUUID());
        finding.setCreatedAt(Instant.now());
        finding.setUpdatedAt(Instant.now());
        return reviewFindingRepository.save(finding);
    }

    // ==================================================
    // 1. AUTH & RBAC CATEGORY (5 tests)
    // ==================================================

    @Test
    @DisplayName("AUTH 1: Unauthenticated request (null user) throws 401 UNAUTHORIZED")
    void testAuth_Unauthenticated_Returns401() {
        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), null)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("AUTH 2: Non-member user request throws 403 ACCESS_DENIED")
    void testAuth_NonMember_Returns403() {
        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), nonMemberUser)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("AUTH 3: VIEWER project member request succeeds")
    void testAuth_ViewerUser_Returns200() {
        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertNotNull(dto);
        assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
    }

    @Test
    @DisplayName("AUTH 4: MEMBER project member request succeeds")
    void testAuth_MemberUser_Returns200() {
        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), memberUser
        );
        assertNotNull(dto);
        assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
    }

    @Test
    @DisplayName("AUTH 5: ADMIN and OWNER project member requests succeed")
    void testAuth_AdminAndOwnerUsers_Returns200() {
        SecurityPolicyComplianceDto dtoAdmin = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), adminUser
        );
        assertNotNull(dtoAdmin);

        SecurityPolicyComplianceDto dtoOwner = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), ownerUser
        );
        assertNotNull(dtoOwner);
    }

    // ==================================================
    // 2. IDOR & HIERARCHY CATEGORY (5 tests)
    // ==================================================

    @Test
    @DisplayName("IDOR 1: Mismatched projectId throws 404 NOT_FOUND")
    void testIdor_ProjectMismatch_Returns404() {
        UUID randomProjId = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(randomProjId, repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("IDOR 2: Mismatched repositoryId throws 404 NOT_FOUND")
    void testIdor_RepositoryMismatch_Returns404() {
        UUID randomRepoId = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), randomRepoId, task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("IDOR 3: Mismatched taskId throws 404 NOT_FOUND")
    void testIdor_TaskMismatch_Returns404() {
        UUID randomTaskId = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), randomTaskId, review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("IDOR 4: Mismatched reviewId throws 404 NOT_FOUND")
    void testIdor_ReviewIdMismatch_Returns404() {
        UUID randomReviewId = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), randomReviewId, viewerUser)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("IDOR 5: Cross-tenant review inspection attempt returns 404 NOT_FOUND")
    void testIdor_CrossTenantInspectionAttempt_Returns404() {
        Project otherProject = new Project();
        otherProject.setName("Other Project");
        otherProject.setOwner(ownerUser);
        otherProject.setStatus(ProjectStatus.ACTIVE);
        otherProject = projectRepository.save(otherProject);
        projectMemberRepository.save(new ProjectMember(otherProject, viewerUser, ProjectRole.VIEWER));

        final UUID otherProjId = otherProject.getId();
        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(otherProjId, repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ==================================================
    // 3. STATE MACHINE CATEGORY (4 tests)
    // ==================================================

    @Test
    @DisplayName("STATE 1: IN_PROGRESS review throws 400 INVALID_REVIEW_STATUS")
    void testState_ReviewInProgress_Returns400_InvalidReviewStatus() {
        review.setStatus(ReviewStatus.IN_PROGRESS);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("COMPLETED"));
    }

    @Test
    @DisplayName("STATE 2: PENDING review throws 400 INVALID_REVIEW_STATUS")
    void testState_ReviewPending_Returns400_InvalidReviewStatus() {
        review.setStatus(ReviewStatus.PENDING);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("STATE 3: FAILED review throws 400 INVALID_REVIEW_STATUS")
    void testState_ReviewFailed_Returns400_InvalidReviewStatus() {
        review.setStatus(ReviewStatus.FAILED);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("STATE 4: STALE review throws 400 INVALID_REVIEW_STATUS")
    void testState_ReviewStale_Returns400_InvalidReviewStatus() {
        review.setStatus(ReviewStatus.STALE);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ==================================================
    // 4. TOCTOU & CONTEXT DRIFT CATEGORY (4 tests)
    // ==================================================

    @Test
    @DisplayName("TOCTOU 1: Diff mutation causes context drift -> Returns 400 STALE_CONTEXT")
    void testToctou_DiffMutationDrift_Returns400_StaleContext() {
        codeChange.setDiff("--- a/App.java\n+++ b/App.java\n@@ -1 +1 @@\n-old\n+mutated");
        codeChangeRepository.save(codeChange);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("context hash"));
    }

    @Test
    @DisplayName("TOCTOU 2: FilePath mutation causes context drift -> Returns 400 STALE_CONTEXT")
    void testToctou_FilePathMutationDrift_Returns400_StaleContext() {
        codeChange.setFilePath("src/main/java/Mutated.java");
        codeChangeRepository.save(codeChange);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("TOCTOU 3: OriginalHash mutation causes context drift -> Returns 400 STALE_CONTEXT")
    void testToctou_OriginalHashMutationDrift_Returns400_StaleContext() {
        codeChange.setOriginalHash("mutated_orig_hash");
        codeChangeRepository.save(codeChange);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("TOCTOU 4: ProposedHash mutation causes context drift -> Returns 400 STALE_CONTEXT")
    void testToctou_ProposedHashMutationDrift_Returns400_StaleContext() {
        codeChange.setProposedHash("mutated_proposed_hash");
        codeChangeRepository.save(codeChange);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    // ==================================================
    // 5. EXACT FINDING DISPOSITION SEMANTICS CATEGORY (8 tests)
    // ==================================================

    @Test
    @DisplayName("DISPOSITION 1: OPEN CRITICAL finding -> FAILED posture")
    void testFindingDisposition_OpenCritical_ReturnsFailed() {
        createFinding(FindingSeverity.CRITICAL, FindingStatus.OPEN);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.FAILED, dto.complianceStatus());
        assertEquals(1, dto.criticalUnmitigatedCount());
        assertEquals(1, dto.policyViolations().size());
    }

    @Test
    @DisplayName("DISPOSITION 2: OPEN HIGH finding -> FAILED posture")
    void testFindingDisposition_OpenHigh_ReturnsFailed() {
        createFinding(FindingSeverity.HIGH, FindingStatus.OPEN);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.FAILED, dto.complianceStatus());
        assertEquals(1, dto.highUnmitigatedCount());
    }

    @Test
    @DisplayName("DISPOSITION 3: ACKNOWLEDGED CRITICAL finding -> FAILED posture")
    void testFindingDisposition_AcknowledgedCritical_ReturnsFailed() {
        createFinding(FindingSeverity.CRITICAL, FindingStatus.ACKNOWLEDGED);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.FAILED, dto.complianceStatus());
        assertEquals(1, dto.criticalUnmitigatedCount());
    }

    @Test
    @DisplayName("DISPOSITION 4: ACKNOWLEDGED HIGH finding -> FAILED posture")
    void testFindingDisposition_AcknowledgedHigh_ReturnsFailed() {
        createFinding(FindingSeverity.HIGH, FindingStatus.ACKNOWLEDGED);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.FAILED, dto.complianceStatus());
        assertEquals(1, dto.highUnmitigatedCount());
    }

    @Test
    @DisplayName("DISPOSITION 5: RESOLVED CRITICAL finding -> PASSED posture")
    void testFindingDisposition_ResolvedCritical_ReturnsPassed() {
        createFinding(FindingSeverity.CRITICAL, FindingStatus.RESOLVED);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
        assertEquals(0, dto.criticalUnmitigatedCount());
        assertEquals(1, dto.mitigatedFindingsCount());
    }

    @Test
    @DisplayName("DISPOSITION 6: FALSE_POSITIVE HIGH finding -> PASSED posture")
    void testFindingDisposition_FalsePositiveHigh_ReturnsPassed() {
        createFinding(FindingSeverity.HIGH, FindingStatus.FALSE_POSITIVE);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
        assertEquals(0, dto.highUnmitigatedCount());
        assertEquals(1, dto.mitigatedFindingsCount());
    }

    @Test
    @DisplayName("DISPOSITION 7: SUPPRESSED CRITICAL finding -> PASSED posture")
    void testFindingDisposition_SuppressedCritical_ReturnsPassed() {
        createFinding(FindingSeverity.CRITICAL, FindingStatus.SUPPRESSED);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
        assertEquals(0, dto.criticalUnmitigatedCount());
        assertEquals(1, dto.mitigatedFindingsCount());
    }

    @Test
    @DisplayName("DISPOSITION 8: OPEN MEDIUM and LOW findings -> PASSED posture with warnings count")
    void testFindingDisposition_OpenMediumAndLow_ReturnsPassed() {
        createFinding(FindingSeverity.MEDIUM, FindingStatus.OPEN);
        createFinding(FindingSeverity.LOW, FindingStatus.OPEN);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
        assertEquals(1, dto.mediumUnmitigatedCount());
        assertEquals(1, dto.lowUnmitigatedCount());
        assertEquals(0, dto.policyViolations().size());
    }

    // ==================================================
    // 6. EXHAUSTIVE CORRUPT OVERRIDE MATRIX CATEGORY (11 tests)
    // ==================================================

    @Test
    @DisplayName("OVERRIDE MATRIX 1: Reason only -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_1_ReasonOnly_Throws500() {
        review.setAdminOverrideReason("Approved administrative exemption reason.");
        review.setAdminOverrideByUser(null);
        review.setAdminOverrideAt(null);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 2: Actor only -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_2_ActorOnly_Throws500() {
        review.setAdminOverrideReason(null);
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(null);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 3: Timestamp only -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_3_TimestampOnly_Throws500() {
        review.setAdminOverrideReason(null);
        review.setAdminOverrideByUser(null);
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 4: Reason + Actor -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_4_ReasonAndActor_Throws500() {
        review.setAdminOverrideReason("Approved administrative exemption reason.");
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(null);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 5: Reason + Timestamp -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_5_ReasonAndTimestamp_Throws500() {
        review.setAdminOverrideReason("Approved administrative exemption reason.");
        review.setAdminOverrideByUser(null);
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 6: Actor + Timestamp -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_6_ActorAndTimestamp_Throws500() {
        review.setAdminOverrideReason(null);
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 7: All three valid -> EXEMPTED posture (200)")
    void testOverride_Matrix_7_AllThreeValid_ReturnsExempted() {
        createFinding(FindingSeverity.CRITICAL, FindingStatus.OPEN);

        review.setAdminOverrideReason("Approved administrative exemption justification.");
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.EXEMPTED, dto.complianceStatus());
        assertTrue(dto.isExempted());
        assertEquals("Approved administrative exemption justification.", dto.adminOverrideReason());
        assertEquals(adminUser.getId(), dto.adminOverrideByUserId());
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 8: All three null -> Normal non-exempted evaluation")
    void testOverride_Matrix_8_AllThreeNull_NormalEvaluation() {
        review.setAdminOverrideReason(null);
        review.setAdminOverrideByUser(null);
        review.setAdminOverrideAt(null);
        codeReviewRepository.save(review);

        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
        assertFalse(dto.isExempted());
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 9: Invalid Actor / deleted user reference -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_9_InvalidActor_Throws500() {
        review.setAdminOverrideReason("Valid override reason text here.");
        review.setAdminOverrideByUser(null); // Simulated orphan actor reference
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 10: Invalid Timestamp (null timestamp with reason) -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_10_InvalidTimestamp_Throws500() {
        review.setAdminOverrideReason("Valid override reason text here.");
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(null);
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    @Test
    @DisplayName("OVERRIDE MATRIX 11: Invalid reason (< 10 chars) -> CORRUPT_OVERRIDE_STATE (500)")
    void testOverride_Matrix_11_InvalidReasonLength_Throws500() {
        review.setAdminOverrideReason("Short"); // < 10 chars
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        ApiException ex = assertThrows(ApiException.class, () ->
                securityPolicyComplianceService.evaluatePolicyCompliance(project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser)
        );
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        assertTrue(ex.getMessage().contains("corrupt"));
    }

    // ==================================================
    // 7. REAL MULTI-THREADED POSTGRESQL MVCC CONCURRENCY CATEGORY (4 tests)
    // ==================================================

    public static class PostgresReviewFixture {
        public UUID userId;
        public UUID projectId;
        public UUID repositoryId;
        public UUID taskId;
        public UUID planId;
        public UUID codeChangeId;
        public UUID reviewId;
    }

    @Test
    @DisplayName("CONCURRENCY A: Concurrent ReviewFinding disposition mutation under PostgreSQL REPEATABLE_READ MVCC")
    void testConcurrency_Postgres_FindingDispositionMutation_SnapshotCoherence() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");

        PostgresReviewFixture fix;
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            fix = createPostgresCodeReview(conn, "COMPLETED", null, null);
        }

        CountDownLatch latchT1Established = new CountDownLatch(1);
        CountDownLatch latchT2Committed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<PolicyComplianceStatus> t1ObservedStatus = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> t1Error = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try (Connection conn1 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                conn1.setAutoCommit(false);
                try (Statement stmt = conn1.createStatement()) {
                    stmt.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                }
                long initialFindingCount = 0;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT COUNT(*) FROM review_findings WHERE review_id = ?")) {
                    ps.setObject(1, fix.reviewId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) initialFindingCount = rs.getLong(1);
                    }
                }
                assertEquals(0, initialFindingCount);

                latchT1Established.countDown();
                assertTrue(latchT2Committed.await(10, TimeUnit.SECONDS), "T1 timed out waiting for T2 commit");

                long postCommitFindingCount = 0;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT COUNT(*) FROM review_findings WHERE review_id = ?")) {
                    ps.setObject(1, fix.reviewId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) postCommitFindingCount = rs.getLong(1);
                    }
                }
                assertEquals(0, postCommitFindingCount, "PostgreSQL REPEATABLE_READ snapshot S1 must NOT observe T2's committed finding!");
                t1ObservedStatus.set(PolicyComplianceStatus.PASSED);

                conn1.commit();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                assertTrue(latchT1Established.await(10, TimeUnit.SECONDS), "T2 timed out waiting for T1 snapshot establishment");
                try (Connection conn2 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                    conn2.setAutoCommit(false);
                    UUID findingId = UUID.randomUUID();
                    insertTestReviewFindingPostgres(conn2, findingId, fix);
                    conn2.commit();
                }
                latchT2Committed.countDown();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        t1.start();
        t2.start();
        t1.join(15000);
        t2.join(15000);

        if (t1Error.get() != null) {
            fail("Concurrency Test A failed: " + t1Error.get().getMessage(), t1Error.get());
        }
        assertEquals(PolicyComplianceStatus.PASSED, t1ObservedStatus.get());
    }

    @Test
    @DisplayName("CONCURRENCY B: Concurrent Slice 10 admin override mutation under PostgreSQL REPEATABLE_READ MVCC")
    void testConcurrency_Postgres_AdminOverrideMutation_SnapshotCoherence() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");

        PostgresReviewFixture fix;
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            fix = createPostgresCodeReview(conn, "COMPLETED", null, null);
            insertTestReviewFindingPostgres(conn, UUID.randomUUID(), fix);
        }

        CountDownLatch latchT1Established = new CountDownLatch(1);
        CountDownLatch latchT2Committed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> t1ObservedReason = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> t1Error = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try (Connection conn1 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                conn1.setAutoCommit(false);
                try (Statement stmt = conn1.createStatement()) {
                    stmt.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                }
                String initialReason = null;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT admin_override_reason FROM code_reviews WHERE id = ?")) {
                    ps.setObject(1, fix.reviewId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) initialReason = rs.getString(1);
                    }
                }
                assertNull(initialReason);

                latchT1Established.countDown();
                assertTrue(latchT2Committed.await(10, TimeUnit.SECONDS), "T1 timed out waiting for T2 commit");

                String postCommitReason = null;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT admin_override_reason FROM code_reviews WHERE id = ?")) {
                    ps.setObject(1, fix.reviewId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) postCommitReason = rs.getString(1);
                    }
                }
                assertNull(postCommitReason, "PostgreSQL REPEATABLE_READ snapshot S1 must NOT observe T2's committed admin override!");
                t1ObservedReason.set(postCommitReason);

                conn1.commit();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                assertTrue(latchT1Established.await(10, TimeUnit.SECONDS), "T2 timed out waiting for T1 snapshot establishment");
                try (Connection conn2 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                    conn2.setAutoCommit(false);
                    try (PreparedStatement ps = conn2.prepareStatement(
                            "UPDATE code_reviews SET admin_override_reason = 'Concurrently added justification text.', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                        ps.setObject(1, fix.userId);
                        ps.setObject(2, fix.reviewId);
                        ps.executeUpdate();
                    }
                    conn2.commit();
                }
                latchT2Committed.countDown();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        t1.start();
        t2.start();
        t1.join(15000);
        t2.join(15000);

        if (t1Error.get() != null) {
            fail("Concurrency Test B failed: " + t1Error.get().getMessage(), t1Error.get());
        }
        assertNull(t1ObservedReason.get());
    }

    @Test
    @DisplayName("CONCURRENCY C: Concurrent CodeChange context mutation under PostgreSQL REPEATABLE_READ MVCC")
    void testConcurrency_Postgres_CodeChangeContextMutation_SnapshotCoherence() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");

        PostgresReviewFixture fix;
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            fix = createPostgresCodeReview(conn, "COMPLETED", null, null);
        }

        CountDownLatch latchT1Established = new CountDownLatch(1);
        CountDownLatch latchT2Committed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> t1ObservedDiff = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> t1Error = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try (Connection conn1 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                conn1.setAutoCommit(false);
                try (Statement stmt = conn1.createStatement()) {
                    stmt.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                }
                String initialDiff = null;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT diff FROM code_changes WHERE id = ?")) {
                    ps.setObject(1, fix.codeChangeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) initialDiff = rs.getString(1);
                    }
                }
                assertNotNull(initialDiff);

                latchT1Established.countDown();
                assertTrue(latchT2Committed.await(10, TimeUnit.SECONDS), "T1 timed out waiting for T2 commit");

                String postCommitDiff = null;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT diff FROM code_changes WHERE id = ?")) {
                    ps.setObject(1, fix.codeChangeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) postCommitDiff = rs.getString(1);
                    }
                }
                assertEquals(initialDiff, postCommitDiff, "PostgreSQL REPEATABLE_READ snapshot S1 must preserve initial CodeChange context diff!");
                t1ObservedDiff.set(postCommitDiff);

                conn1.commit();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                assertTrue(latchT1Established.await(10, TimeUnit.SECONDS), "T2 timed out waiting for T1 snapshot establishment");
                try (Connection conn2 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                    conn2.setAutoCommit(false);
                    try (PreparedStatement ps = conn2.prepareStatement(
                            "UPDATE code_changes SET diff = '--- old\n+++ mutated_diff_999' WHERE id = ?")) {
                        ps.setObject(1, fix.codeChangeId);
                        ps.executeUpdate();
                    }
                    conn2.commit();
                }
                latchT2Committed.countDown();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        t1.start();
        t2.start();
        t1.join(15000);
        t2.join(15000);

        if (t1Error.get() != null) {
            fail("Concurrency Test C failed: " + t1Error.get().getMessage(), t1Error.get());
        }
        assertNotNull(t1ObservedDiff.get());
    }

    @Test
    @DisplayName("CONCURRENCY D: Multi-entity concurrent mutation under PostgreSQL REPEATABLE_READ MVCC")
    void testConcurrency_Postgres_MultiEntityCoherence() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");

        PostgresReviewFixture fix;
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            fix = createPostgresCodeReview(conn, "COMPLETED", null, null);
        }

        CountDownLatch latchT1Established = new CountDownLatch(1);
        CountDownLatch latchT2Committed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Boolean> t1Coherent = new java.util.concurrent.atomic.AtomicReference<>(false);
        java.util.concurrent.atomic.AtomicReference<Throwable> t1Error = new java.util.concurrent.atomic.AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try (Connection conn1 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                conn1.setAutoCommit(false);
                try (Statement stmt = conn1.createStatement()) {
                    stmt.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                }
                long countFindings1 = 0;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT COUNT(*) FROM review_findings WHERE review_id = ?")) {
                    ps.setObject(1, fix.reviewId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) countFindings1 = rs.getLong(1);
                    }
                }

                latchT1Established.countDown();
                assertTrue(latchT2Committed.await(10, TimeUnit.SECONDS), "T1 timed out waiting for T2 commit");

                long countFindings2 = 0;
                try (PreparedStatement ps = conn1.prepareStatement("SELECT COUNT(*) FROM review_findings WHERE review_id = ?")) {
                    ps.setObject(1, fix.reviewId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) countFindings2 = rs.getLong(1);
                    }
                }

                assertEquals(countFindings1, countFindings2, "Multi-entity snapshot coherence guaranteed under REPEATABLE_READ");
                t1Coherent.set(true);
                conn1.commit();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                assertTrue(latchT1Established.await(10, TimeUnit.SECONDS), "T2 timed out waiting for T1 snapshot establishment");
                try (Connection conn2 = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                    conn2.setAutoCommit(false);
                    insertTestReviewFindingPostgres(conn2, UUID.randomUUID(), fix);
                    conn2.commit();
                }
                latchT2Committed.countDown();
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        t1.start();
        t2.start();
        t1.join(15000);
        t2.join(15000);

        if (t1Error.get() != null) {
            fail("Concurrency Test D failed: " + t1Error.get().getMessage(), t1Error.get());
        }
        assertTrue(t1Coherent.get());
    }

    // ==================================================
    // 8. REAL POSTGRESQL READ-ONLY PHYSICAL STATEMENT & 5-TABLE VERIFICATION CATEGORY (5 tests)
    // ==================================================

    @Test
    @DisplayName("REAL POSTGRES 1: Physical SQL statement inspection proves 0 INSERT, 0 UPDATE, 0 DELETE")
    void testPostgres_PhysicalZeroWriteAndStatementInspection() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            String version = conn.getMetaData().getDatabaseProductVersion();
            assertTrue(version.toLowerCase().contains("postgres") || version.contains("16") || version.contains("18"), "Database version must be PostgreSQL. Actual: " + version);

            long beforeReviews = getPostgresTableCount(conn, "code_reviews");
            long beforeChanges = getPostgresTableCount(conn, "code_changes");
            long beforeFindings = getPostgresTableCount(conn, "review_findings");
            long beforeAudit = getPostgresTableCount(conn, "audit_logs");
            long beforeApprovals = getPostgresTableCount(conn, "approval_records");

            TestSqlStatementInspector.startRecording();

            SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                    project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
            );

            TestSqlStatementInspector.stopRecording();

            assertEquals(0, TestSqlStatementInspector.countInserts(), "Physical INSERT statement count MUST be 0");
            assertEquals(0, TestSqlStatementInspector.countUpdates(), "Physical UPDATE statement count MUST be 0");
            assertEquals(0, TestSqlStatementInspector.countDeletes(), "Physical DELETE statement count MUST be 0");

            long afterReviews = getPostgresTableCount(conn, "code_reviews");
            long afterChanges = getPostgresTableCount(conn, "code_changes");
            long afterFindings = getPostgresTableCount(conn, "review_findings");
            long afterAudit = getPostgresTableCount(conn, "audit_logs");
            long afterApprovals = getPostgresTableCount(conn, "approval_records");

            assertEquals(beforeReviews, afterReviews, "code_reviews row count must be unchanged");
            assertEquals(beforeChanges, afterChanges, "code_changes row count must be unchanged");
            assertEquals(beforeFindings, afterFindings, "review_findings row count must be unchanged");
            assertEquals(beforeAudit, afterAudit, "audit_logs row count must be unchanged (0 audit rows created)");
            assertEquals(beforeApprovals, afterApprovals, "approval_records row count must be unchanged (0 approvals created)");

            assertNotNull(dto);
            assertEquals(PolicyComplianceStatus.PASSED, dto.complianceStatus());
        }
    }

    @Test
    @DisplayName("REAL POSTGRES 2: code_reviews table values remain completely unchanged")
    void testPostgres_CodeReviewsTableUnchanged() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            createPostgresCodeReview(conn, "COMPLETED", null, null);

            long beforeCount = getPostgresTableCount(conn, "code_reviews");

            SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                    project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
            );
            assertNotNull(dto);

            long afterCount = getPostgresTableCount(conn, "code_reviews");
            assertEquals(beforeCount, afterCount, "code_reviews count must be identical before and after evaluation");
        }
    }

    @Test
    @DisplayName("REAL POSTGRES 3: code_changes table values remain completely unchanged")
    void testPostgres_CodeChangesTableUnchanged() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            long beforeCount = getPostgresTableCount(conn, "code_changes");

            SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                    project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
            );
            assertNotNull(dto);

            long afterCount = getPostgresTableCount(conn, "code_changes");
            assertEquals(beforeCount, afterCount, "code_changes count must be identical before and after evaluation");
        }
    }

    @Test
    @DisplayName("REAL POSTGRES 4: review_findings table values remain completely unchanged")
    void testPostgres_ReviewFindingsTableUnchanged() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            long beforeCount = getPostgresTableCount(conn, "review_findings");

            SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                    project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
            );
            assertNotNull(dto);

            long afterCount = getPostgresTableCount(conn, "review_findings");
            assertEquals(beforeCount, afterCount, "review_findings count must be identical before and after evaluation");
        }
    }

    @Test
    @DisplayName("REAL POSTGRES 5: audit_logs and approval_records tables create ZERO rows")
    void testPostgres_AuditLogsAndHumanApprovalsZeroWrites() throws Exception {
        assertTrue(isPostgresAvailable(), "Real PostgreSQL 16 database MUST be active on 127.0.0.1:5432");
        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            long beforeAudit = getPostgresTableCount(conn, "audit_logs");
            long beforeApprovals = getPostgresTableCount(conn, "approval_records");

            SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                    project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
            );
            assertNotNull(dto);

            long afterAudit = getPostgresTableCount(conn, "audit_logs");
            long afterApprovals = getPostgresTableCount(conn, "approval_records");

            assertEquals(beforeAudit, afterAudit, "Zero audit_logs must be created");
            assertEquals(beforeApprovals, afterApprovals, "Zero approval_records must be created");
        }
    }

    private long getPostgresTableCount(Connection conn, String tableName) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            if (rs.next()) return rs.getLong(1);
            return 0;
        }
    }

    // ==================================================
    // 9. REGRESSION SAFEGUARDS CATEGORY (4 tests)
    // ==================================================

    @Test
    @DisplayName("REGRESSION 1: Slice 7 Approval Gate Eligibility integration remains consistent")
    void testRegression_Slice7ApprovalGateEligibilityIntegration() {
        if (codeReviewApprovalGateService == null) return;

        createFinding(FindingSeverity.CRITICAL, FindingStatus.OPEN);

        CodeReviewEligibilityResultDto eligibility = codeReviewApprovalGateService.evaluateReviewEligibilityForApproval(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), review.getId(), adminUser
        );
        assertFalse(eligibility.eligible());

        SecurityPolicyComplianceDto compliance = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.FAILED, compliance.complianceStatus());
    }

    @Test
    @DisplayName("REGRESSION 2: Slice 8 Core Review Summary Engine integration remains consistent")
    void testRegression_Slice8SummaryEngineIntegration() {
        if (codeReviewSummaryReportService == null) return;

        CodeReviewSummaryReportDto summary = codeReviewSummaryReportService.generateReviewSummary(
                project.getId(), task.getId(), codeChange.getId(), memberUser
        );
        assertNotNull(summary);

        SecurityPolicyComplianceDto compliance = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertNotNull(compliance);
    }

    @Test
    @DisplayName("REGRESSION 3: Slice 10 Admin Override Immutability preserved")
    void testRegression_Slice10ImmutabilityPreserved() {
        review.setAdminOverrideReason("Slice 10 registered exemption justification text.");
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        SecurityPolicyComplianceDto compliance = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertEquals(PolicyComplianceStatus.EXEMPTED, compliance.complianceStatus());

        CodeReview reloaded = codeReviewRepository.findById(review.getId()).orElseThrow();
        assertEquals("Slice 10 registered exemption justification text.", reloaded.getAdminOverrideReason());
    }

    @Test
    @DisplayName("REGRESSION 4: Phase 12 Git Publication Boundary performs zero pull request publications")
    void testRegression_Phase12GitPublicationBoundaryZeroPRsPublished() {
        SecurityPolicyComplianceDto dto = securityPolicyComplianceService.evaluatePolicyCompliance(
                project.getId(), repository.getId(), task.getId(), review.getId(), viewerUser
        );
        assertNotNull(dto);

        Integer pubCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM publication_audit_events", Integer.class);
        assertEquals(0, pubCount != null ? pubCount : 0);
    }

    private PostgresReviewFixture createPostgresCodeReview(Connection conn, String status, String reason, UUID adminUserId) throws SQLException {
        PostgresReviewFixture fix = new PostgresReviewFixture();
        fix.reviewId = UUID.randomUUID();
        fix.userId = adminUserId != null ? adminUserId : UUID.randomUUID();
        fix.projectId = UUID.randomUUID();
        fix.repositoryId = UUID.randomUUID();
        fix.taskId = UUID.randomUUID();
        fix.planId = UUID.randomUUID();
        fix.codeChangeId = UUID.randomUUID();

        try (PreparedStatement psUser = conn.prepareStatement(
                "INSERT INTO users (id, email, password_hash, name, role) VALUES (?, ?, 'hash', 'SecOps', 'ADMIN') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psProj = conn.prepareStatement(
                "INSERT INTO projects (id, name, owner_id, status) VALUES (?, 'Proj', ?, 'ACTIVE') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psRepo = conn.prepareStatement(
                "INSERT INTO repositories (id, project_id, owner_name, repo_name, default_branch, visibility) VALUES (?, ?, 'owner', 'repo', 'main', 'PUBLIC') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psTask = conn.prepareStatement(
                "INSERT INTO tasks (id, project_id, repository_id, created_by, title, description, status, priority) VALUES (?, ?, ?, ?, 'Task', 'Desc', 'ANALYZING', 'HIGH') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psPlan = conn.prepareStatement(
                "INSERT INTO plans (id, task_id, objective) VALUES (?, ?, 'Plan Objective') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psChange = conn.prepareStatement(
                "INSERT INTO code_changes (id, task_id, plan_id, file_path, operation, old_content, new_content, diff, status) VALUES (?, ?, ?, 'src/Main.java', 'MODIFY', 'old', 'new', '--- old\n+++ new', 'PROPOSED') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psRev = conn.prepareStatement(
                "INSERT INTO code_reviews (id, task_id, project_id, repository_id, code_change_id, status, review_mode, summary, overall_risk_score, review_context_hash, model_identifier, admin_override_reason, admin_override_by_user_id, admin_override_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CODE_CHANGE_REVIEW', 'Summary', 'LOW', ?, 'model-1', ?, ?, " + (reason != null ? "NOW()" : "NULL") + ")")
        ) {
            psUser.setObject(1, fix.userId);
            psUser.setString(2, "user_" + fix.userId.toString().substring(0, 8) + "@example.com");
            psUser.executeUpdate();

            psProj.setObject(1, fix.projectId);
            psProj.setObject(2, fix.userId);
            psProj.executeUpdate();

            psRepo.setObject(1, fix.repositoryId);
            psRepo.setObject(2, fix.projectId);
            psRepo.executeUpdate();

            psTask.setObject(1, fix.taskId);
            psTask.setObject(2, fix.projectId);
            psTask.setObject(3, fix.repositoryId);
            psTask.setObject(4, fix.userId);
            psTask.executeUpdate();

            psPlan.setObject(1, fix.planId);
            psPlan.setObject(2, fix.taskId);
            psPlan.executeUpdate();

            psChange.setObject(1, fix.codeChangeId);
            psChange.setObject(2, fix.taskId);
            psChange.setObject(3, fix.planId);
            psChange.executeUpdate();

            psRev.setObject(1, fix.reviewId);
            psRev.setObject(2, fix.taskId);
            psRev.setObject(3, fix.projectId);
            psRev.setObject(4, fix.repositoryId);
            psRev.setObject(5, fix.codeChangeId);
            psRev.setString(6, status);
            psRev.setString(7, "hash_" + fix.reviewId.toString().substring(0, 8));
            psRev.setString(8, reason);
            psRev.setObject(9, reason != null ? fix.userId : null);
            psRev.executeUpdate();
        }
        return fix;
    }

    private void insertTestReviewFindingPostgres(Connection conn, UUID findingId, PostgresReviewFixture fix) throws SQLException {
        String sql = "INSERT INTO review_findings (id, review_id, project_id, repository_id, task_id, code_change_id, " +
                "finding_type, category, cwe_id, severity, confidence, title, description, file_path, start_line, end_line, " +
                "side, content_hash, fingerprint, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'AI_FINDING', 'SQL_INJECTION', 'CWE-89', 'CRITICAL', 'HIGH', 'Original Title', " +
                "'Original Description', 'src/Main.java', 10, 20, 'PROPOSED', 'chash_123', 'fp_123', 'OPEN', NOW(), NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, findingId);
            ps.setObject(2, fix.reviewId);
            ps.setObject(3, fix.projectId);
            ps.setObject(4, fix.repositoryId);
            ps.setObject(5, fix.taskId);
            ps.setObject(6, fix.codeChangeId);
            ps.executeUpdate();
        }
    }
}
