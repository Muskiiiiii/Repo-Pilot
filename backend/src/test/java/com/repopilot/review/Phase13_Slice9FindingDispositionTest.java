package com.repopilot.review;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
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
import com.repopilot.review.dto.CodeReviewEligibilityResultDto;
import com.repopilot.review.dto.ReviewFindingResponseDto;
import com.repopilot.review.dto.UpdateFindingDispositionRequestDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.CodeReviewApprovalGateService;
import com.repopilot.review.service.ReviewFindingService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase13_Slice9FindingDispositionTest {

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
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private CodeReviewRepository codeReviewRepository;

    @Autowired
    private ReviewFindingRepository reviewFindingRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ReviewFindingService reviewFindingService;

    @Autowired
    private CodeReviewApprovalGateService codeReviewApprovalGateService;

    @Autowired
    private com.repopilot.review.service.ReviewContextService reviewContextService;

    @Autowired
    private com.repopilot.project.service.ProjectAuthorizationService projectAuthorizationService;

    @Autowired
    private com.repopilot.security.service.PreLlmSecretScanner preLlmSecretScanner;

    @Autowired(required = false)
    private com.repopilot.audit.service.AuditLogService auditLogService;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private User ownerUser;
    private User adminUser;
    private User memberUser;
    private User viewerUser;
    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private CodeReview codeReview;
    private ReviewFinding finding;

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Project Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        adminUser = new User();
        adminUser.setEmail("admin_" + UUID.randomUUID() + "@example.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("Project Admin");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);

        memberUser = new User();
        memberUser.setEmail("member_" + UUID.randomUUID() + "@example.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Project Member");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        viewerUser = new User();
        viewerUser.setEmail("viewer_" + UUID.randomUUID() + "@example.com");
        viewerUser.setPasswordHash("hash");
        viewerUser.setName("Project Viewer");
        viewerUser.setRole(UserRole.USER);
        viewerUser = userRepository.save(viewerUser);

        project = new Project();
        project.setName("Phase 13 Slice 9 Test Project");
        project.setDescription("Project for Slice 9 testing");
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
        task.setCreatedBy(ownerUser);
        task.setTitle("Slice 9 Finding Triage Task");
        task.setDescription("Task for finding disposition");
        task.setStatus(TaskStatus.CREATED);
        task.setPriority(TaskPriority.HIGH);
        task = taskRepository.save(task);

        codeChange = new CodeChange();
        codeChange.setRepository(repository);
        codeChange.setTask(task);
        codeChange.setFilePath("src/main/java/App.java");
        codeChange.setOperation("MODIFY");
        codeChange.setDiff("--- a/App.java\n+++ b/App.java\n@@ -1 +1 @@\n-old\n+new");
        codeChange.setOriginalHash("hash111111111111111111111111111111111111111111111111111111111111");
        codeChange.setProposedHash("hash222222222222222222222222222222222222222222222222222222222222");
        codeChange = codeChangeRepository.save(codeChange);

        com.repopilot.review.dto.ReviewContext ctx = reviewContextService.buildReviewContext(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser
        );
        codeReview = new CodeReview();
        codeReview.setProject(project);
        codeReview.setRepository(repository);
        codeReview.setTask(task);
        codeReview.setCodeChange(codeChange);
        codeReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        codeReview.setStatus(ReviewStatus.COMPLETED);
        codeReview.setModelIdentifier("gemini-2.5-pro");
        codeReview.setReviewContextHash(ctx != null ? ctx.reviewContextHash() : "context_hash_99999999999999999999999999999999999999");
        codeReview = codeReviewRepository.save(codeReview);

        finding = new ReviewFinding();
        finding.setProject(project);
        finding.setRepository(repository);
        finding.setTask(task);
        finding.setCodeChange(codeChange);
        finding.setReview(codeReview);
        finding.setCategory("SECURITY");
        finding.setCweId("CWE-798");
        finding.setSeverity(FindingSeverity.CRITICAL);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setTitle("Hardcoded Password Found");
        finding.setDescription("Found sensitive credential");
        finding.setFilePath("src/main/java/App.java");
        finding.setStartLine(10);
        finding.setEndLine(12);
        finding.setSide(FindingSide.PROPOSED);
        finding.setContentHash("content_hash_111111111111111111111111111111111111111111111111");
        finding.setFingerprint("fingerprint_111111111111111111111111111111111111111111111111");
        finding.setStatus(FindingStatus.OPEN);
        finding = reviewFindingRepository.save(finding);
    }

    @AfterEach
    void tearDown() {
        auditLogRepository.deleteAllInBatch();
        reviewFindingRepository.deleteAllInBatch();
        codeReviewRepository.deleteAllInBatch();
        codeChangeRepository.deleteAllInBatch();
        taskRepository.deleteAllInBatch();
        repositoryRepository.deleteAllInBatch();
        projectMemberRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("Member role can transition OPEN -> ACKNOWLEDGED")
    void testMemberRoleAcknowledgeFinding() {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledging vulnerability for triage"
        );

        ReviewFindingResponseDto result = reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, memberUser
        );

        assertNotNull(result);
        assertEquals(FindingStatus.ACKNOWLEDGED, result.getStatus());

        ReviewFinding updated = reviewFindingRepository.findById(finding.getId()).orElseThrow();
        assertEquals(FindingStatus.ACKNOWLEDGED, updated.getStatus());

        List<AuditLog> auditLogs = auditLogRepository.findAll();
        assertFalse(auditLogs.isEmpty());
        AuditLog log = auditLogs.get(auditLogs.size() - 1);
        assertEquals("FINDING_DISPOSITION_UPDATED", log.getAction());
        assertEquals("REVIEW_FINDING", log.getResourceType());
        assertEquals(finding.getId(), log.getResourceId());
    }

    @Test
    @DisplayName("Member role can transition OPEN -> RESOLVED with valid reason")
    void testMemberRoleResolveFinding() {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "Fixed hardcoded password by introducing secret vault injection"
        );

        ReviewFindingResponseDto result = reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, memberUser
        );

        assertEquals(FindingStatus.RESOLVED, result.getStatus());
    }

    @Test
    @DisplayName("Member role denied FALSE_POSITIVE transition (Admin/Owner required)")
    void testMemberRoleDeniedFalsePositive() {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.FALSE_POSITIVE, "This password is a false positive test value"
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, memberUser
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("ADMIN_ROLE_REQUIRED", ex.getCode());
    }

    @Test
    @DisplayName("Member role denied SUPPRESSED transition (Admin/Owner required)")
    void testMemberRoleDeniedSuppressed() {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.SUPPRESSED, "Suppressing this security finding for testing"
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, memberUser
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("ADMIN_ROLE_REQUIRED", ex.getCode());
    }

    @Test
    @DisplayName("Admin and Owner roles allowed FALSE_POSITIVE and SUPPRESSED")
    void testAdminAndOwnerAllowedAdministrativeDispositions() {
        UpdateFindingDispositionRequestDto reqFalsePos = new UpdateFindingDispositionRequestDto(
                FindingStatus.FALSE_POSITIVE, "Verified false positive by security admin"
        );

        ReviewFindingResponseDto adminResult = reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), reqFalsePos, adminUser
        );
        assertEquals(FindingStatus.FALSE_POSITIVE, adminResult.getStatus());

        UpdateFindingDispositionRequestDto reqSuppressed = new UpdateFindingDispositionRequestDto(
                FindingStatus.SUPPRESSED, "Suppressed per compliance exemption #12345"
        );

        ReviewFindingResponseDto ownerResult = reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), reqSuppressed, ownerUser
        );
        assertEquals(FindingStatus.SUPPRESSED, ownerResult.getStatus());
    }

    @Test
    @DisplayName("Viewer role denied all finding disposition mutations")
    void testViewerRoleDeniedMutations() {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Attempting to acknowledge"
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, viewerUser
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("No-op request returns 200 OK with ZERO DB updates and ZERO audit events")
    void testAuthorizedNoOpReturnsCurrentStateZeroAudit() {
        long initialAuditCount = auditLogRepository.count();

        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.OPEN, "Ignored reason on no-op"
        );

        ReviewFindingResponseDto result = reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, memberUser
        );

        assertEquals(FindingStatus.OPEN, result.getStatus());

        long finalAuditCount = auditLogRepository.count();
        assertEquals(initialAuditCount, finalAuditCount, "No-op must emit zero audit logs");
    }

    @Test
    @DisplayName("Unauthorized user calling no-op is denied (Authorization enforced on no-op)")
    void testUnauthorizedNoOpDenied() {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.OPEN, "No-op attempt by viewer"
        );

        assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, viewerUser
                )
        );
    }

    @Test
    @DisplayName("Reason length validation rules (min lengths per target status and max length 1000)")
    void testReasonLengthValidation() {
        // RESOLVED < 5 chars -> fail
        UpdateFindingDispositionRequestDto shortResolved = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "fix"
        );
        ApiException ex1 = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), shortResolved, memberUser
                )
        );
        assertEquals("REASON_TOO_SHORT", ex1.getCode());

        // FALSE_POSITIVE < 10 chars -> fail
        UpdateFindingDispositionRequestDto shortFalsePos = new UpdateFindingDispositionRequestDto(
                FindingStatus.FALSE_POSITIVE, "short"
        );
        ApiException ex2 = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), shortFalsePos, adminUser
                )
        );
        assertEquals("REASON_TOO_SHORT", ex2.getCode());

        // SUPPRESSED < 10 chars -> fail
        UpdateFindingDispositionRequestDto shortSuppressed = new UpdateFindingDispositionRequestDto(
                FindingStatus.SUPPRESSED, "suppress"
        );
        ApiException ex3 = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), shortSuppressed, adminUser
                )
        );
        assertEquals("REASON_TOO_SHORT", ex3.getCode());

        // > 1000 chars -> fail
        String over1000 = "a".repeat(1001);
        UpdateFindingDispositionRequestDto longReq = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, over1000
        );
        ApiException ex4 = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), longReq, memberUser
                )
        );
        assertEquals("REASON_TOO_LONG", ex4.getCode());
    }

    @Test
    @DisplayName("Secret redaction in justification text strips raw secrets prior to persistence")
    void testSecretRedactionInReasonText() {
        String rawSecretReason = "Verified false positive for secret AKIAIOSFODNN7EXAMPLE in test fixture";
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.FALSE_POSITIVE, rawSecretReason
        );

        reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, adminUser
        );

        List<AuditLog> auditLogs = auditLogRepository.findAll();
        assertFalse(auditLogs.isEmpty());
        AuditLog log = auditLogs.get(auditLogs.size() - 1);
        assertFalse(log.getMetadata().contains("AKIAIOSFODNN7EXAMPLE"), "Raw AWS key must be redacted");
        assertTrue(log.getMetadata().contains("[REDACTED_SECRET_PRE_LLM]"), "Redacted token marker must exist");
    }

    @Test
    @DisplayName("Post-lock hierarchy validation prevents cross-review / mismatched task IDOR")
    void testPostLockHierarchyValidationMismatches() {
        UUID randomReviewId = UUID.randomUUID();
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Triage note"
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), randomReviewId, finding.getId(), req, memberUser
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("Pessimistic locking serializes concurrent mutations on the same ReviewFinding row")
    void testConcurrentSameFindingMutations() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<ReviewFindingResponseDto> task1 = () -> reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(),
                new UpdateFindingDispositionRequestDto(FindingStatus.RESOLVED, "Resolution reason for concurrent thread 1"),
                adminUser
        );

        Callable<ReviewFindingResponseDto> task2 = () -> reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(),
                new UpdateFindingDispositionRequestDto(FindingStatus.FALSE_POSITIVE, "False positive reason for concurrent thread 2"),
                adminUser
        );

        List<Future<ReviewFindingResponseDto>> futures = executor.invokeAll(List.of(task1, task2));
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        for (Future<ReviewFindingResponseDto> f : futures) {
            assertNotNull(f.get());
        }

        ReviewFinding finalFinding = reviewFindingRepository.findById(finding.getId()).orElseThrow();
        assertTrue(finalFinding.getStatus() == FindingStatus.RESOLVED || finalFinding.getStatus() == FindingStatus.FALSE_POSITIVE);
    }

    @Test
    @DisplayName("Slice 7 Gate Integration: RESOLVED/FALSE_POSITIVE/SUPPRESSED unblock gate, ACKNOWLEDGED remains blocking")
    void testSlice7GateIntegrationWithFindingDispositions() {
        // Initially CRITICAL finding is OPEN -> Gate ineligible
        CodeReviewEligibilityResultDto el1 = codeReviewApprovalGateService.evaluateReviewEligibilityForApproval(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), codeReview.getId(), memberUser
        );
        assertFalse(el1.eligible(), "OPEN critical finding must block review approval");

        // Transition to ACKNOWLEDGED -> Gate STILL ineligible
        reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(),
                new UpdateFindingDispositionRequestDto(FindingStatus.ACKNOWLEDGED, "Acknowledging for review"),
                memberUser
        );
        CodeReviewEligibilityResultDto el2 = codeReviewApprovalGateService.evaluateReviewEligibilityForApproval(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), codeReview.getId(), memberUser
        );
        assertFalse(el2.eligible(), "ACKNOWLEDGED critical finding must still block review approval");

        // Transition to RESOLVED -> Gate becomes ELIGIBLE
        reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(),
                new UpdateFindingDispositionRequestDto(FindingStatus.RESOLVED, "Resolved finding by code refactor"),
                memberUser
        );
        CodeReviewEligibilityResultDto el3 = codeReviewApprovalGateService.evaluateReviewEligibilityForApproval(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), codeReview.getId(), memberUser
        );
        assertTrue(el3.eligible(), "RESOLVED status must unblock review approval eligibility");
    }

    // ==========================================
    // COMPLETE 50-CELL MATRIX PARAMETERIZED TEST
    // ==========================================

    public static class MatrixCell {
        public final FindingStatus currentStatus;
        public final FindingStatus targetStatus;
        public final ProjectRole role;
        public final boolean expectedAllowed;
        public final String reason;

        public MatrixCell(FindingStatus currentStatus, FindingStatus targetStatus, ProjectRole role, boolean expectedAllowed, String reason) {
            this.currentStatus = currentStatus;
            this.targetStatus = targetStatus;
            this.role = role;
            this.expectedAllowed = expectedAllowed;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return String.format("[%s -> %s | %s => %s]", currentStatus, targetStatus, role, expectedAllowed ? "ALLOWED" : "DENIED");
        }
    }

    public static List<MatrixCell> provide50CellMatrix() {
        List<MatrixCell> cells = new ArrayList<>();
        FindingStatus[] statuses = FindingStatus.values();
        // 25 cells for MEMBER
        for (FindingStatus cur : statuses) {
            for (FindingStatus tgt : statuses) {
                boolean allowed;
                if (cur == tgt) {
                    allowed = true; // no-op allowed for member
                } else if (cur == FindingStatus.FALSE_POSITIVE || cur == FindingStatus.SUPPRESSED) {
                    allowed = false; // member cannot transition out of FP/SUPPRESSED
                } else if (tgt == FindingStatus.FALSE_POSITIVE || tgt == FindingStatus.SUPPRESSED) {
                    allowed = false; // member cannot transition into FP/SUPPRESSED
                } else {
                    allowed = true; // OPEN <-> ACKNOWLEDGED <-> RESOLVED
                }
                String reason = "Valid justification reason text for testing matrix cell";
                cells.add(new MatrixCell(cur, tgt, ProjectRole.MEMBER, allowed, reason));
            }
        }
        // 25 cells for ADMIN
        for (FindingStatus cur : statuses) {
            for (FindingStatus tgt : statuses) {
                String reason = "Valid justification reason text for testing matrix cell";
                cells.add(new MatrixCell(cur, tgt, ProjectRole.ADMIN, true, reason));
            }
        }
        return cells;
    }

    @ParameterizedTest
    @MethodSource("provide50CellMatrix")
    @DisplayName("50-Cell Matrix: Complete State/Role Matrix Verification")
    void test50CellStateRoleMatrix(MatrixCell cell) {
        ReviewFinding f = reviewFindingRepository.findById(finding.getId()).orElseThrow();
        f.setStatus(cell.currentStatus);
        reviewFindingRepository.saveAndFlush(f);

        User userToUse = (cell.role == ProjectRole.MEMBER) ? memberUser : adminUser;
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(cell.targetStatus, cell.reason);

        if (cell.expectedAllowed) {
            ReviewFindingResponseDto res = reviewFindingService.updateFindingDisposition(
                    project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, userToUse
            );
            assertNotNull(res);
            assertEquals(cell.targetStatus, res.getStatus());
        } else {
            ApiException ex = assertThrows(ApiException.class, () ->
                    reviewFindingService.updateFindingDisposition(
                            project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, userToUse
                    )
            );
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
            assertEquals("ADMIN_ROLE_REQUIRED", ex.getCode());
        }
    }

    // ==========================================
    // OWNER-VS-ADMIN EQUIVALENCE PARAMETERIZED TEST
    // ==========================================

    public static List<MatrixCell> provide25OwnerAdminEquivalenceTransitions() {
        List<MatrixCell> cells = new ArrayList<>();
        FindingStatus[] statuses = FindingStatus.values();
        for (FindingStatus cur : statuses) {
            for (FindingStatus tgt : statuses) {
                String reason = "Valid justification reason text for testing owner/admin equivalence";
                cells.add(new MatrixCell(cur, tgt, ProjectRole.OWNER, true, reason));
            }
        }
        return cells;
    }

    @ParameterizedTest
    @MethodSource("provide25OwnerAdminEquivalenceTransitions")
    @DisplayName("Role Equivalence: OWNER has identical 25-cell ALLOWED authority as ADMIN")
    void testOwnerAdminEquivalenceAcrossAllTransitions(MatrixCell cell) {
        ReviewFinding f = reviewFindingRepository.findById(finding.getId()).orElseThrow();
        f.setStatus(cell.currentStatus);
        reviewFindingRepository.saveAndFlush(f);

        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(cell.targetStatus, cell.reason);

        // Execute with OWNER user
        ReviewFindingResponseDto ownerRes = reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, ownerUser
        );
        assertNotNull(ownerRes);
        assertEquals(cell.targetStatus, ownerRes.getStatus());

        // Reset status for ADMIN execution
        f.setStatus(cell.currentStatus);
        reviewFindingRepository.saveAndFlush(f);

        // Execute with ADMIN user
        ReviewFindingResponseDto adminRes = reviewFindingService.updateFindingDisposition(
                project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, adminUser
        );
        assertNotNull(adminRes);
        assertEquals(cell.targetStatus, adminRes.getStatus());
    }

    // ==========================================
    // AUDIT TRANSACTION ROLLBACK TESTS
    // ==========================================

    @Test
    @DisplayName("Audit Transaction Rollback: Forced audit failure rolls back finding mutation")
    void testAuditFailureRollsBackFindingUpdate() {
        com.repopilot.audit.service.AuditLogService failingAuditService = (user, prj, tsk, action, resType, resId, meta) -> {
            throw new RuntimeException("Simulated audit log write failure");
        };

        com.repopilot.review.service.ReviewFindingServiceImpl failingFindingService = new com.repopilot.review.service.ReviewFindingServiceImpl(
                projectAuthorizationService,
                repositoryRepository,
                taskRepository,
                codeReviewRepository,
                reviewFindingRepository,
                preLlmSecretScanner,
                failingAuditService
        );

        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "Resolution reason for rollback test"
        );

        assertThrows(RuntimeException.class, () ->
                failingFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), req, memberUser
                )
        );

        ReviewFinding unchangedFinding = reviewFindingRepository.findById(finding.getId()).orElseThrow();
        assertEquals(FindingStatus.OPEN, unchangedFinding.getStatus(), "Finding status must remain OPEN after audit failure transaction rollback");
    }

    @Test
    @DisplayName("Finding persistence failure: No orphan audit row created on invalid request")
    void testFindingPersistenceFailureNoOrphanAudit() {
        long initialAuditCount = auditLogRepository.count();

        UpdateFindingDispositionRequestDto invalidReq = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "bad" // short reason < 5 chars
        );

        assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), task.getId(), codeReview.getId(), finding.getId(), invalidReq, memberUser
                )
        );

        long finalAuditCount = auditLogRepository.count();
        assertEquals(initialAuditCount, finalAuditCount, "No orphan audit log row must be created on finding validation failure");
    }

    @Test
    @DisplayName("Finding persistence boundary failure: Database save failure rolls back transaction with zero orphan audit log")
    void testFindingPersistenceFailureRollsBackAndNoOrphanAudit() {
        ReviewFinding initialFinding = reviewFindingRepository.findById(finding.getId()).orElseThrow();
        assertEquals(FindingStatus.OPEN, initialFinding.getStatus());
        java.time.Instant initialUpdatedAt = initialFinding.getUpdatedAt();

        ReviewFindingRepository failingFindingRepo = new ReviewFindingRepository() {
            @Override
            public <S extends ReviewFinding> S save(S entity) {
                throw new org.springframework.dao.DataIntegrityViolationException(
                        "Simulated database constraint failure during ReviewFinding save"
                );
            }
            @Override public List<ReviewFinding> findByReview(CodeReview review) { return reviewFindingRepository.findByReview(review); }
            @Override public List<ReviewFinding> findByReviewId(UUID reviewId) { return reviewFindingRepository.findByReviewId(reviewId); }
            @Override public List<ReviewFinding> findByReviewIdIn(List<UUID> reviewIds) { return reviewFindingRepository.findByReviewIdIn(reviewIds); }
            @Override public List<ReviewFinding> findByTaskId(UUID taskId) { return reviewFindingRepository.findByTaskId(taskId); }
            @Override public List<ReviewFinding> findByCodeChangeId(UUID codeChangeId) { return reviewFindingRepository.findByCodeChangeId(codeChangeId); }
            @Override public List<ReviewFinding> findByFingerprint(String fingerprint) { return reviewFindingRepository.findByFingerprint(fingerprint); }
            @Override public Optional<ReviewFinding> findByReviewIdAndFingerprint(UUID reviewId, String fingerprint) { return reviewFindingRepository.findByReviewIdAndFingerprint(reviewId, fingerprint); }
            @Override public Optional<ReviewFinding> findByIdForUpdate(UUID id) { return reviewFindingRepository.findByIdForUpdate(id); }
            @Override public Optional<ReviewFinding> findByIdAndReviewIdForUpdate(UUID findingId, UUID reviewId) { return reviewFindingRepository.findByIdAndReviewIdForUpdate(findingId, reviewId); }
            @Override public List<ReviewFinding> findByProjectIdAndStatus(UUID projectId, FindingStatus status) { return reviewFindingRepository.findByProjectIdAndStatus(projectId, status); }
            @Override public List<ReviewFinding> findByProjectIdAndRepositoryIdAndTaskId(UUID projectId, UUID repositoryId, UUID taskId) { return reviewFindingRepository.findByProjectIdAndRepositoryIdAndTaskId(projectId, repositoryId, taskId); }
            @Override public List<ReviewFinding> findByProjectIdAndRepositoryIdAndTaskIdAndCodeChangeId(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId) { return reviewFindingRepository.findByProjectIdAndRepositoryIdAndTaskIdAndCodeChangeId(projectId, repositoryId, taskId, codeChangeId); }
            @Override public List<FindingSeverityCountProjection> countFindingsBySeverityForReview(UUID reviewId) { return reviewFindingRepository.countFindingsBySeverityForReview(reviewId); }
            @Override public List<ReviewFinding> findBoundedSnippetsForReview(UUID reviewId, org.springframework.data.domain.Pageable pageable) { return reviewFindingRepository.findBoundedSnippetsForReview(reviewId, pageable); }
            @Override public void flush() { reviewFindingRepository.flush(); }
            @Override public <S extends ReviewFinding> S saveAndFlush(S entity) { return reviewFindingRepository.saveAndFlush(entity); }
            @Override public <S extends ReviewFinding> List<S> saveAllAndFlush(Iterable<S> entities) { return reviewFindingRepository.saveAllAndFlush(entities); }
            @Override public void deleteAllInBatch(Iterable<ReviewFinding> entities) { reviewFindingRepository.deleteAllInBatch(entities); }
            @Override public void deleteAllByIdInBatch(Iterable<UUID> uuids) { reviewFindingRepository.deleteAllByIdInBatch(uuids); }
            @Override public void deleteAllInBatch() { reviewFindingRepository.deleteAllInBatch(); }
            @Override public ReviewFinding getOne(UUID uuid) { return reviewFindingRepository.getOne(uuid); }
            @Override public ReviewFinding getById(UUID uuid) { return reviewFindingRepository.getById(uuid); }
            @Override public ReviewFinding getReferenceById(UUID uuid) { return reviewFindingRepository.getReferenceById(uuid); }
            @Override public <S extends ReviewFinding> List<S> findAll(org.springframework.data.domain.Example<S> example) { return reviewFindingRepository.findAll(example); }
            @Override public <S extends ReviewFinding> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { return reviewFindingRepository.findAll(example, sort); }
            @Override public <S extends ReviewFinding> List<S> saveAll(Iterable<S> entities) { return reviewFindingRepository.saveAll(entities); }
            @Override public List<ReviewFinding> findAll() { return reviewFindingRepository.findAll(); }
            @Override public List<ReviewFinding> findAllById(Iterable<UUID> uuids) { return reviewFindingRepository.findAllById(uuids); }
            @Override public Optional<ReviewFinding> findById(UUID uuid) { return reviewFindingRepository.findById(uuid); }
            @Override public boolean existsById(UUID uuid) { return reviewFindingRepository.existsById(uuid); }
            @Override public long count() { return reviewFindingRepository.count(); }
            @Override public void deleteById(UUID uuid) { reviewFindingRepository.deleteById(uuid); }
            @Override public void delete(ReviewFinding entity) { reviewFindingRepository.delete(entity); }
            @Override public void deleteAllById(Iterable<? extends UUID> uuids) { reviewFindingRepository.deleteAllById(uuids); }
            @Override public void deleteAll(Iterable<? extends ReviewFinding> entities) { reviewFindingRepository.deleteAll(entities); }
            @Override public void deleteAll() { reviewFindingRepository.deleteAll(); }
            @Override public List<ReviewFinding> findAll(org.springframework.data.domain.Sort sort) { return reviewFindingRepository.findAll(sort); }
            @Override public org.springframework.data.domain.Page<ReviewFinding> findAll(org.springframework.data.domain.Pageable pageable) { return reviewFindingRepository.findAll(pageable); }
            @Override public <S extends ReviewFinding> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { return reviewFindingRepository.findOne(example); }
            @Override public <S extends ReviewFinding> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { return reviewFindingRepository.findAll(example, pageable); }
            @Override public <S extends ReviewFinding> long count(org.springframework.data.domain.Example<S> example) { return reviewFindingRepository.count(example); }
            @Override public <S extends ReviewFinding> boolean exists(org.springframework.data.domain.Example<S> example) { return reviewFindingRepository.exists(example); }
            @Override public <S extends ReviewFinding, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return reviewFindingRepository.findBy(example, queryFunction); }
            @Override public long countTotalFindingsByReviewId(UUID reviewId) { return reviewFindingRepository.countTotalFindingsByReviewId(reviewId); }
            @Override public long countUnmitigatedFindingsBySeverityAndReviewId(UUID reviewId, com.repopilot.review.entity.FindingSeverity severity) { return reviewFindingRepository.countUnmitigatedFindingsBySeverityAndReviewId(reviewId, severity); }
            @Override public long countMitigatedFindingsByReviewId(UUID reviewId) { return reviewFindingRepository.countMitigatedFindingsByReviewId(reviewId); }
            @Override public List<ReviewFinding> findBlockingViolationsByReviewId(UUID reviewId, org.springframework.data.domain.Pageable pageable) { return reviewFindingRepository.findBlockingViolationsByReviewId(reviewId, pageable); }
        };

        com.repopilot.review.service.ReviewFindingServiceImpl failingService = new com.repopilot.review.service.ReviewFindingServiceImpl(
                projectAuthorizationService,
                repositoryRepository,
                taskRepository,
                codeReviewRepository,
                failingFindingRepo,
                preLlmSecretScanner,
                auditLogService
        );

        UpdateFindingDispositionRequestDto validReq = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "Valid resolution justification passing all pre-persistence checks"
        );

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                transactionTemplate.execute(status ->
                        failingService.updateFindingDisposition(
                                project.getId(), task.getId(), codeReview.getId(), finding.getId(), validReq, memberUser
                        )
                )
        );

        ReviewFinding dbFindingAfterFailure = reviewFindingRepository.findById(finding.getId()).orElseThrow();
        assertEquals(FindingStatus.OPEN, dbFindingAfterFailure.getStatus(),
                "ReviewFinding status in DB must remain unchanged (OPEN) after persistence rollback");
        assertEquals(initialUpdatedAt, dbFindingAfterFailure.getUpdatedAt(),
                "ReviewFinding updatedAt timestamp in DB must remain unchanged after persistence rollback");

        long orphanAuditCount = auditLogRepository.findAll().stream()
                .filter(log -> finding.getId().equals(log.getResourceId()) && "FINDING_DISPOSITION_UPDATED".equals(log.getAction()))
                .count();
        assertEquals(0, orphanAuditCount, "Zero orphan FINDING_DISPOSITION_UPDATED audit log row must exist in DB");
    }

    // ==========================================
    // POST-LOCK HIERARCHY VALIDATION TESTS
    // ==========================================

    @Test
    @DisplayName("Post-lock hierarchy validation blocks task IDOR boundary mismatch")
    void testPostLockTaskBoundaryMismatchBlocked() {
        UUID otherTaskId = UUID.randomUUID();
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledge note"
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        project.getId(), otherTaskId, codeReview.getId(), finding.getId(), req, memberUser
                )
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("Post-lock hierarchy validation blocks project IDOR boundary mismatch")
    void testPostLockProjectBoundaryMismatchBlocked() {
        UUID otherProjectId = UUID.randomUUID();
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledge note"
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                reviewFindingService.updateFindingDisposition(
                        otherProjectId, task.getId(), codeReview.getId(), finding.getId(), req, memberUser
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
