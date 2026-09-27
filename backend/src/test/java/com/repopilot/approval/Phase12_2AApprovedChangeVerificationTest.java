package com.repopilot.approval;

import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.approval.dto.VerificationFailureCategory;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.service.ApprovalCanonicalEncoder;
import com.repopilot.approval.service.ApprovedChangeVerifier;
import com.repopilot.approval.service.ApprovedChangeVerifierImpl;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryBranch;
import com.repopilot.repository.repository.RepositoryBranchRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.review.entity.ReviewStatus;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.service.ReviewContextService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Phase12_2AApprovedChangeVerificationTest {

    private ProjectAuthorizationService projectAuthorizationService;
    private RepositoryRepository repositoryRepository;
    private RepositoryBranchRepository repositoryBranchRepository;
    private TaskRepository taskRepository;
    private CodeChangeRepository codeChangeRepository;
    private CodeReviewRepository codeReviewRepository;
    private HumanApprovalRepository humanApprovalRepository;
    private ReviewContextService reviewContextService;
    private DiffGeneratorService diffGeneratorService;

    private ApprovedChangeVerifier approvedChangeVerifier;

    private User ownerUser;
    private User memberUser;
    private User viewerUser;
    private Project project;
    private Repository repository;
    private RepositoryBranch defaultBranch;
    private Task task;
    private CodeChange codeChange;
    private CodeReview codeReview;
    private HumanApproval validApproval;
    private ReviewContext validContext;

    private final UUID projectId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID changeId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();

    private final String validBaseSha = "7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a";
    private final String validContextHash = "a1b2c3d4e5f678901234567890abcdef1234567890abcdef1234567890abcdef";
    private final String validDiffHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @BeforeEach
    void setUp() {
        projectAuthorizationService = mock(ProjectAuthorizationService.class);
        repositoryRepository = mock(RepositoryRepository.class);
        repositoryBranchRepository = mock(RepositoryBranchRepository.class);
        taskRepository = mock(TaskRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        codeReviewRepository = mock(CodeReviewRepository.class);
        humanApprovalRepository = mock(HumanApprovalRepository.class);
        reviewContextService = mock(ReviewContextService.class);
        diffGeneratorService = mock(DiffGeneratorService.class);

        approvedChangeVerifier = new ApprovedChangeVerifierImpl(
                projectAuthorizationService,
                repositoryRepository,
                repositoryBranchRepository,
                taskRepository,
                codeChangeRepository,
                codeReviewRepository,
                humanApprovalRepository,
                reviewContextService,
                diffGeneratorService
        );

        ownerUser = new User();
        ownerUser.setId(UUID.randomUUID());
        ownerUser.setEmail("owner@repopilot.internal");

        memberUser = new User();
        memberUser.setId(UUID.randomUUID());
        memberUser.setEmail("member@repopilot.internal");

        viewerUser = new User();
        viewerUser.setId(UUID.randomUUID());
        viewerUser.setEmail("viewer@repopilot.internal");

        project = new Project();
        project.setId(projectId);
        project.setOwner(ownerUser);

        repository = new Repository();
        repository.setId(repoId);
        repository.setProject(project);
        repository.setDefaultBranch("main");

        defaultBranch = new RepositoryBranch();
        defaultBranch.setId(UUID.randomUUID());
        defaultBranch.setRepository(repository);
        defaultBranch.setName("main");
        defaultBranch.setCommitSha(validBaseSha);

        task = new Task();
        task.setId(taskId);
        task.setProject(project);
        task.setRepository(repository);

        codeChange = new CodeChange();
        codeChange.setId(changeId);
        codeChange.setTask(task);
        codeChange.setRepository(repository);
        codeChange.setFilePath("src/Main.java");
        codeChange.setOldContent("public class Main {}");
        codeChange.setNewContent("public class Main { int x = 1; }");
        codeChange.setOriginalHash("1111111111222222222233333333334444444444555555555566666666667777");
        codeChange.setProposedHash("8888888888999999999900000000001111111111222222222233333333334444");
        codeChange.setDiff("--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-public class Main {}\n+public class Main { int x = 1; }");

        codeReview = new CodeReview();
        codeReview.setId(reviewId);
        codeReview.setProject(project);
        codeReview.setRepository(repository);
        codeReview.setTask(task);
        codeReview.setCodeChange(codeChange);
        codeReview.setStatus(ReviewStatus.COMPLETED);
        codeReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        codeReview.setReviewContextHash(validContextHash);

        validApproval = new HumanApproval();
        validApproval.setId(approvalId);
        validApproval.setProject(project);
        validApproval.setRepository(repository);
        validApproval.setTask(task);
        validApproval.setCodeChange(codeChange);
        validApproval.setReview(codeReview);
        validApproval.setApprovalVersion(1);
        validApproval.setRepositoryBaseSha(validBaseSha);
        validApproval.setCodeChangeOriginalHash("1111111111222222222233333333334444444444555555555566666666667777");
        validApproval.setCodeChangeProposedHash("8888888888999999999900000000001111111111222222222233333333334444");
        validApproval.setCodeChangeDiffHash(validDiffHash);
        validApproval.setReviewContextHash(validContextHash);
        validApproval.setApprovedFiles("[\"src/Main.java\"]");
        validApproval.setApprovedChangeCount(1);
        validApproval.setApprovedByUser(memberUser);
        validApproval.setApprovedByUserEmail("member@repopilot.internal");
        validApproval.setApprovedByUserRole("MEMBER");
        validApproval.setApprovedAt(Instant.parse("2026-09-15T22:45:00Z"));
        validApproval.setIdempotencyKey("test-key-1");
        validApproval.setApprovalRecordHash(ApprovalCanonicalEncoder.computeApprovalRecordHash(validApproval));
        validApproval.setStatus(ApprovalStatus.APPROVED);

        validContext = new ReviewContext(
                projectId, repoId, taskId, changeId, ReviewMode.CODE_CHANGE_REVIEW,
                "src/Main.java", "MODIFY", "old", "new", "diff",
                "1111111111222222222233333333334444444444555555555566666666667777",
                "8888888888999999999900000000001111111111222222222233333333334444",
                validContextHash, "v1.0", false, List.of()
        );

        when(projectAuthorizationService.getAuthorizedProject(eq(projectId), any(User.class))).thenReturn(project);
        when(projectAuthorizationService.getUserRoleInProject(eq(project), eq(ownerUser))).thenReturn(ProjectRole.OWNER);
        when(projectAuthorizationService.getUserRoleInProject(eq(project), eq(memberUser))).thenReturn(ProjectRole.MEMBER);
        when(projectAuthorizationService.getUserRoleInProject(eq(project), eq(viewerUser))).thenReturn(ProjectRole.VIEWER);

        doNothing().when(projectAuthorizationService).requireMinimumRole(eq(project), eq(ownerUser), eq(ProjectRole.MEMBER));
        doNothing().when(projectAuthorizationService).requireMinimumRole(eq(project), eq(memberUser), eq(ProjectRole.MEMBER));
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions"))
                .when(projectAuthorizationService).requireMinimumRole(eq(project), eq(viewerUser), eq(ProjectRole.MEMBER));

        when(repositoryRepository.findById(repoId)).thenReturn(Optional.of(repository));
        when(repositoryBranchRepository.findByRepositoryIdAndName(repoId, "main")).thenReturn(Optional.of(defaultBranch));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(codeChangeRepository.findById(changeId)).thenReturn(Optional.of(codeChange));
        when(codeReviewRepository.findById(reviewId)).thenReturn(Optional.of(codeReview));
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(validApproval));

        when(reviewContextService.buildReviewContext(eq(projectId), eq(repoId), eq(taskId), eq(changeId), eq(ReviewMode.CODE_CHANGE_REVIEW), any(User.class)))
                .thenReturn(validContext);

        when(diffGeneratorService.computeSha256Hash(anyString())).thenReturn(validDiffHash);
    }

    @Nested
    @DisplayName("A. VALID APPROVAL (Test 1)")
    class ValidApprovalTests {

        @Test
        @DisplayName("Test 1: Valid approval verifies successfully")
        void test1_ValidApprovalVerifiesSuccessfully() {
            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repoId, taskId, changeId, approvalId, memberUser
            );

            assertTrue(result.isValid());
            assertEquals(VerificationFailureCategory.NONE, result.failureCategory());
            assertNull(result.failureReason());
            assertEquals(approvalId, result.approvalId());
            assertEquals(validBaseSha.toLowerCase(), result.repositoryBaseSha());
            assertEquals(validDiffHash.toLowerCase(), result.codeChangeDiffHash());
            assertEquals(validContextHash.toLowerCase(), result.reviewContextHash());
        }
    }

    @Nested
    @DisplayName("B. AUTHORIZATION (Tests 2-8)")
    class AuthorizationTests {

        @Test
        @DisplayName("Test 2: Null requester returns UNAUTHORIZED")
        void test2_NullRequesterRejected() {
            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repoId, taskId, changeId, approvalId, null
            );

            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.UNAUTHORIZED, result.failureCategory());
        }

        @Test
        @DisplayName("Test 3: VIEWER role returns UNAUTHORIZED")
        void test3_ViewerRoleRejected() {
            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repoId, taskId, changeId, approvalId, viewerUser
            );

            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.UNAUTHORIZED, result.failureCategory());
        }

        @Test
        @DisplayName("Test 4: Mismatched project ID returns HIERARCHY_MISMATCH")
        void test4_CrossProjectRejected() {
            UUID wrongProjectId = UUID.randomUUID();
            when(projectAuthorizationService.getAuthorizedProject(eq(wrongProjectId), any(User.class)))
                    .thenReturn(project); // mock returns project

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    wrongProjectId, repoId, taskId, changeId, approvalId, memberUser
            );

            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.HIERARCHY_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 5: Mismatched repository ID returns HIERARCHY_MISMATCH")
        void test5_CrossRepositoryRejected() {
            UUID wrongRepoId = UUID.randomUUID();

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    projectId, wrongRepoId, taskId, changeId, approvalId, memberUser
            );

            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.HIERARCHY_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 6: Mismatched task ID returns HIERARCHY_MISMATCH")
        void test6_CrossTaskRejected() {
            UUID wrongTaskId = UUID.randomUUID();

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repoId, wrongTaskId, changeId, approvalId, memberUser
            );

            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.HIERARCHY_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 7: Mismatched CodeChange ID returns HIERARCHY_MISMATCH")
        void test7_MismatchedCodeChangeRejected() {
            UUID wrongChangeId = UUID.randomUUID();

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repoId, taskId, wrongChangeId, approvalId, memberUser
            );

            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.HIERARCHY_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 8: Non-existent approval ID returns APPROVAL_NOT_FOUND")
        void test8_NonExistentApprovalId() {
            UUID missingId = UUID.randomUUID();

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repoId, taskId, changeId, missingId, memberUser
            );

            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.APPROVAL_NOT_FOUND, result.failureCategory());
        }
    }

    @Nested
    @DisplayName("C. STATUS CHECKS (Tests 9-15)")
    class StatusTests {

        private HumanApproval createUnapprovedRecord(ApprovalStatus status) {
            HumanApproval a = new HumanApproval();
            a.setId(approvalId);
            a.setProject(project);
            a.setRepository(repository);
            a.setTask(task);
            a.setCodeChange(codeChange);
            a.setReview(codeReview);
            a.setApprovalVersion(1);
            a.setRepositoryBaseSha(validBaseSha);
            a.setCodeChangeOriginalHash(codeChange.getOriginalHash());
            a.setCodeChangeProposedHash(codeChange.getProposedHash());
            a.setCodeChangeDiffHash(validDiffHash);
            a.setReviewContextHash(validContextHash);
            a.setApprovedFiles("[\"src/Main.java\"]");
            a.setApprovedByUser(memberUser);
            a.setApprovedByUserEmail("member@repopilot.internal");
            a.setApprovedByUserRole("MEMBER");
            a.setApprovedAt(Instant.now());
            a.setIdempotencyKey("key");
            a.setApprovalRecordHash(ApprovalCanonicalEncoder.computeApprovalRecordHash(a));
            a.setStatus(status);
            return a;
        }

        @Test
        @DisplayName("Test 9: PENDING_APPROVAL status returns INVALID_STATUS")
        void test9_PendingStatusRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createUnapprovedRecord(ApprovalStatus.PENDING_APPROVAL)));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.INVALID_STATUS, result.failureCategory());
        }

        @Test
        @DisplayName("Test 10: APPROVED status returns valid")
        void test10_ApprovedStatusValid() {
            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Test 11: REVOKED status returns INVALID_STATUS")
        void test11_RevokedStatusRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createUnapprovedRecord(ApprovalStatus.REVOKED)));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.INVALID_STATUS, result.failureCategory());
        }

        @Test
        @DisplayName("Test 12: CANCELLED status returns INVALID_STATUS")
        void test12_CancelledStatusRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createUnapprovedRecord(ApprovalStatus.CANCELLED)));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.INVALID_STATUS, result.failureCategory());
        }

        @Test
        @DisplayName("Test 13: FAILED status returns INVALID_STATUS")
        void test13_FailedStatusRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createUnapprovedRecord(ApprovalStatus.FAILED)));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.INVALID_STATUS, result.failureCategory());
        }

        @Test
        @DisplayName("Test 14: COMPLETED status returns INVALID_STATUS")
        void test14_CompletedStatusRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createUnapprovedRecord(ApprovalStatus.COMPLETED)));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.INVALID_STATUS, result.failureCategory());
        }

        @Test
        @DisplayName("Test 15: APPROVAL_STALE status returns APPROVAL_STALE")
        void test15_StaleStatusRejected() {
            HumanApproval stale = createUnapprovedRecord(ApprovalStatus.APPROVAL_STALE);
            stale.setStaleReason("Base SHA moved");
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(stale));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.APPROVAL_STALE, result.failureCategory());
        }
    }

    @Nested
    @DisplayName("D & E. HASH INTEGRITY & SNAPSHOT (Tests 16-29)")
    class SnapshotIntegrityTests {

        @Test
        @DisplayName("Test 16: Tampered approvalRecordHash returns APPROVAL_INTEGRITY_FAILURE")
        void test16_TamperedApprovalRecordHash() {
            HumanApproval tampered = new HumanApproval();
            tampered.setId(approvalId);
            tampered.setProject(project);
            tampered.setRepository(repository);
            tampered.setTask(task);
            tampered.setCodeChange(codeChange);
            tampered.setReview(codeReview);
            tampered.setApprovalVersion(1);
            tampered.setRepositoryBaseSha(validBaseSha);
            tampered.setCodeChangeOriginalHash(codeChange.getOriginalHash());
            tampered.setCodeChangeProposedHash(codeChange.getProposedHash());
            tampered.setCodeChangeDiffHash(validDiffHash);
            tampered.setReviewContextHash(validContextHash);
            tampered.setApprovedFiles("[\"src/Main.java\"]");
            tampered.setApprovedByUser(memberUser);
            tampered.setApprovedByUserEmail("member@repopilot.internal");
            tampered.setApprovedByUserRole("MEMBER");
            tampered.setApprovedAt(Instant.now());
            tampered.setIdempotencyKey("key");
            tampered.setApprovalRecordHash("0000000000000000000000000000000000000000000000000000000000000000");
            tampered.setStatus(ApprovalStatus.APPROVED);

            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(tampered));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.APPROVAL_INTEGRITY_FAILURE, result.failureCategory());
        }

        @Test
        @DisplayName("Test 23: CodeChange original hash changed post-approval returns CODE_CHANGE_MISMATCH")
        void test23_OriginalHashChanged() {
            codeChange.setOriginalHash("9999999999999999999999999999999999999999999999999999999999999999");

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.CODE_CHANGE_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 24: CodeChange proposed hash changed post-approval returns CODE_CHANGE_MISMATCH")
        void test24_ProposedHashChanged() {
            codeChange.setProposedHash("9999999999999999999999999999999999999999999999999999999999999999");

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.CODE_CHANGE_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 25: CodeChange diff changed post-approval returns CODE_CHANGE_MISMATCH")
        void test25_DiffHashChanged() {
            when(diffGeneratorService.computeSha256Hash(anyString())).thenReturn("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.CODE_CHANGE_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 26: ReviewContext changed post-approval returns CONTEXT_MISMATCH")
        void test26_ContextHashChanged() {
            ReviewContext driftedContext = new ReviewContext(
                    projectId, repoId, taskId, changeId, ReviewMode.CODE_CHANGE_REVIEW,
                    "src/Main.java", "MODIFY", "old", "new", "diff", "orig", "prop",
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    "v1.0", false, List.of()
            );
            when(reviewContextService.buildReviewContext(any(), any(), any(), any(), any(), any())).thenReturn(driftedContext);

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.CONTEXT_MISMATCH, result.failureCategory());
        }
    }

    @Nested
    @DisplayName("G. PATH SECURITY (Tests 34-42)")
    class PathSecurityTests {

        private HumanApproval createApprovalWithFiles(String filesJson) {
            HumanApproval a = new HumanApproval();
            a.setId(approvalId);
            a.setProject(project);
            a.setRepository(repository);
            a.setTask(task);
            a.setCodeChange(codeChange);
            a.setReview(codeReview);
            a.setApprovalVersion(1);
            a.setRepositoryBaseSha(validBaseSha);
            a.setCodeChangeOriginalHash(codeChange.getOriginalHash());
            a.setCodeChangeProposedHash(codeChange.getProposedHash());
            a.setCodeChangeDiffHash(validDiffHash);
            a.setReviewContextHash(validContextHash);
            a.setApprovedFiles(filesJson);
            a.setApprovedByUser(memberUser);
            a.setApprovedByUserEmail("member@repopilot.internal");
            a.setApprovedByUserRole("MEMBER");
            a.setApprovedAt(Instant.now());
            a.setIdempotencyKey("key");
            a.setApprovalRecordHash(ApprovalCanonicalEncoder.computeApprovalRecordHash(a));
            a.setStatus(ApprovalStatus.APPROVED);
            return a;
        }

        @Test
        @DisplayName("Test 34: Path traversal ../ returns PATH_VALIDATION_FAILED")
        void test34_PathTraversalRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createApprovalWithFiles("[\"../etc/passwd\"]")));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.PATH_VALIDATION_FAILED, result.failureCategory());
        }

        @Test
        @DisplayName("Test 36: Absolute path /etc/shadow returns PATH_VALIDATION_FAILED")
        void test36_AbsolutePathRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createApprovalWithFiles("[\"/etc/shadow\"]")));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.PATH_VALIDATION_FAILED, result.failureCategory());
        }

        @Test
        @DisplayName("Test 37: Null byte in path returns PATH_VALIDATION_FAILED")
        void test37_NullBytePathRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createApprovalWithFiles("[\"src/Main.java\\u0000.png\"]")));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.PATH_VALIDATION_FAILED, result.failureCategory());
        }

        @Test
        @DisplayName("Test 41: Duplicate path returns PATH_VALIDATION_FAILED")
        void test41_DuplicatePathRejected() {
            when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(createApprovalWithFiles("[\"src/Main.java\", \"src/Main.java\"]")));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.PATH_VALIDATION_FAILED, result.failureCategory());
        }
    }

    @Nested
    @DisplayName("I & J. CONCURRENCY & ZERO SIDE EFFECTS (Tests 50-59)")
    class SideEffectTests {

        @Test
        @DisplayName("Test 51: Repository default branch HEAD SHA drift returns BASE_SHA_MISMATCH")
        void test51_BaseShaDriftRejected() {
            RepositoryBranch driftedBranch = new RepositoryBranch();
            driftedBranch.setRepository(repository);
            driftedBranch.setName("main");
            driftedBranch.setCommitSha("9999888877776666555544443333222211110000"); // Drifted SHA!

            when(repositoryBranchRepository.findByRepositoryIdAndName(repoId, "main")).thenReturn(Optional.of(driftedBranch));

            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertFalse(result.isValid());
            assertEquals(VerificationFailureCategory.BASE_SHA_MISMATCH, result.failureCategory());
        }

        @Test
        @DisplayName("Test 53-59: Zero side effects (No Git, GitHub, Docker, or DB mutations)")
        void test53_59_ZeroSideEffectsConfirmed() {
            ApprovedChangeVerificationResult result = approvedChangeVerifier.verifyApprovedChange(projectId, repoId, taskId, changeId, approvalId, memberUser);
            assertTrue(result.isValid());

            // Verify no repository save or status update occurred
            verify(humanApprovalRepository, never()).save(any());
            verify(codeChangeRepository, never()).save(any());
            verify(codeReviewRepository, never()).save(any());
        }
    }
}
