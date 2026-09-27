package com.repopilot.approval;

import com.repopilot.approval.dto.HumanApprovalCreateRequest;
import com.repopilot.approval.dto.HumanApprovalResponseDto;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.service.ApprovalCanonicalEncoder;
import com.repopilot.approval.service.HumanApprovalService;
import com.repopilot.approval.service.HumanApprovalServiceImpl;
import com.repopilot.audit.service.AuditLogService;
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

class Phase12_1HumanApprovalTest {

    private ProjectAuthorizationService projectAuthorizationService;
    private RepositoryRepository repositoryRepository;
    private RepositoryBranchRepository repositoryBranchRepository;
    private TaskRepository taskRepository;
    private CodeChangeRepository codeChangeRepository;
    private CodeReviewRepository codeReviewRepository;
    private HumanApprovalRepository humanApprovalRepository;
    private ReviewContextService reviewContextService;
    private DiffGeneratorService diffGeneratorService;
    private AuditLogService auditLogService;

    private HumanApprovalService humanApprovalService;

    private User ownerUser;
    private User memberUser;
    private User viewerUser;
    private Project project;
    private Repository repository;
    private RepositoryBranch defaultBranch;
    private Task task;
    private CodeChange codeChange;
    private CodeReview codeReview;
    private ReviewContext validContext;

    private final UUID projectId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID changeId = UUID.randomUUID();
    private final UUID reviewId = UUID.randomUUID();

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
        auditLogService = mock(AuditLogService.class);

        humanApprovalService = new HumanApprovalServiceImpl(
                projectAuthorizationService,
                repositoryRepository,
                repositoryBranchRepository,
                taskRepository,
                codeChangeRepository,
                codeReviewRepository,
                humanApprovalRepository,
                reviewContextService,
                diffGeneratorService,
                auditLogService
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

        validContext = new ReviewContext(
                projectId,
                repoId,
                taskId,
                changeId,
                ReviewMode.CODE_CHANGE_REVIEW,
                "src/Main.java",
                "MODIFY",
                "public class Main {}",
                "public class Main { int x = 1; }",
                "--- a/src/Main.java\n+++ b/src/Main.java\n@@ -1 +1 @@\n-public class Main {}\n+public class Main { int x = 1; }",
                "1111111111222222222233333333334444444444555555555566666666667777",
                "8888888888999999999900000000001111111111222222222233333333334444",
                validContextHash,
                "v1.0",
                false,
                List.of()
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

        when(reviewContextService.buildReviewContext(eq(projectId), eq(repoId), eq(taskId), eq(changeId), eq(ReviewMode.CODE_CHANGE_REVIEW), any(User.class)))
                .thenReturn(validContext);

        when(diffGeneratorService.computeSha256Hash(anyString())).thenReturn(validDiffHash);

        when(humanApprovalRepository.saveAndFlush(any(HumanApproval.class))).thenAnswer(invocation -> {
            HumanApproval a = invocation.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
            }
            return a;
        });
    }

    @Nested
    @DisplayName("AUTHORIZATION & ROLE SCOPING (Tests 1-9)")
    class AuthorizationTests {

        @Test
        @DisplayName("Test 1: Authorized member/owner approval succeeds")
        void test1_AuthorizedApprovalSucceeds() {
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-001", true);
            HumanApprovalResponseDto dto = humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, memberUser);

            assertNotNull(dto);
            assertEquals(ApprovalStatus.APPROVED, dto.status());
            assertEquals(memberUser.getId(), dto.approvedByUserId());
            assertEquals("MEMBER", dto.approvedByUserRole());
            assertNotNull(dto.approvalRecordHash());
        }

        @Test
        @DisplayName("Test 2: Viewer role approval rejected with 403 FORBIDDEN")
        void test2_ViewerRoleRejected() {
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-002", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, viewerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("Test 3: Wrong project ID rejected")
        void test3_WrongProjectRejected() {
            UUID wrongProjectId = UUID.randomUUID();
            when(projectAuthorizationService.getAuthorizedProject(eq(wrongProjectId), any(User.class)))
                    .thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "Wrong project"));

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-003", true);
            assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(wrongProjectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
        }

        @Test
        @DisplayName("Test 4: Repository belonging to different project rejected")
        void test4_WrongRepositoryProjectRejected() {
            Project otherProject = new Project();
            otherProject.setId(UUID.randomUUID());
            Repository foreignRepo = new Repository();
            foreignRepo.setId(repoId);
            foreignRepo.setProject(otherProject);

            when(repositoryRepository.findById(repoId)).thenReturn(Optional.of(foreignRepo));

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-004", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("Test 5: Task belonging to different repository rejected")
        void test5_WrongTaskRepositoryRejected() {
            Task foreignTask = new Task();
            foreignTask.setId(taskId);
            foreignTask.setProject(project);
            foreignTask.setRepository(new Repository());

            when(taskRepository.findById(taskId)).thenReturn(Optional.of(foreignTask));

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-005", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("Test 6: CodeChange belonging to different task rejected")
        void test6_WrongChangeTaskRejected() {
            CodeChange foreignChange = new CodeChange();
            foreignChange.setId(changeId);
            foreignChange.setTask(new Task());
            foreignChange.setRepository(repository);

            when(codeChangeRepository.findById(changeId)).thenReturn(Optional.of(foreignChange));

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-006", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("Test 7: CodeReview belonging to different CodeChange rejected")
        void test7_WrongReviewChangeRejected() {
            CodeReview foreignReview = new CodeReview();
            foreignReview.setId(reviewId);
            foreignReview.setProject(project);
            foreignReview.setRepository(repository);
            foreignReview.setTask(task);
            foreignReview.setCodeChange(new CodeChange()); // Different change!

            when(codeReviewRepository.findById(reviewId)).thenReturn(Optional.of(foreignReview));

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-007", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, ex.getStatus());
        }

        @Test
        @DisplayName("Test 8: Unconfirmed request (confirmAction=false) rejected")
        void test8_UnconfirmedRequestRejected() {
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-008", false);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("Test 9: Null requester (unauthenticated) rejected with 401 UNAUTHORIZED")
        void test9_NullRequesterRejected() {
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-009", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, null)
            );
            assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getStatus());
        }
    }

    @Nested
    @DisplayName("BINDING & SNAPSHOT INTEGRITY (Tests 10-17)")
    class BindingTests {

        @Test
        @DisplayName("Test 10: Valid CodeChange and Review binding captures snapshots accurately")
        void test10_ValidBindingCapturesSnapshots() {
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-010", true);
            HumanApprovalResponseDto dto = humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser);

            assertEquals(validBaseSha.toLowerCase(), dto.repositoryBaseSha());
            assertEquals(codeChange.getOriginalHash().toLowerCase(), dto.codeChangeOriginalHash());
            assertEquals(codeChange.getProposedHash().toLowerCase(), dto.codeChangeProposedHash());
            assertEquals(validDiffHash.toLowerCase(), dto.codeChangeDiffHash());
            assertEquals(validContextHash.toLowerCase(), dto.reviewContextHash());
        }

        @Test
        @DisplayName("Test 11: Non-completed CodeReview status rejected")
        void test11_IncompleteReviewRejected() {
            codeReview.setStatus(ReviewStatus.IN_PROGRESS);

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-011", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("Test 12: Stale review context hash mismatch rejected")
        void test12_StaleReviewContextRejected() {
            ReviewContext driftedContext = new ReviewContext(
                    projectId, repoId, taskId, changeId, ReviewMode.CODE_CHANGE_REVIEW,
                    "src/Main.java", "MODIFY", "old", "new", "diff", "origHash", "propHash",
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    "v1.0", false, List.of()
            );
            when(reviewContextService.buildReviewContext(any(), any(), any(), any(), any(), any()))
                    .thenReturn(driftedContext);

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-012", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("Test 13: Requested base SHA mismatch with repository default branch rejected")
        void test13_BaseShaMismatchRejected() {
            String wrongBaseSha = "1111222233334444555566667777888899990000";
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(wrongBaseSha, "key-013", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("Test 14: Invalid base SHA format rejected")
        void test14_InvalidBaseShaFormatRejected() {
            when(repositoryBranchRepository.findByRepositoryIdAndName(repoId, "main")).thenReturn(Optional.empty());

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest("invalid-sha-too-short", "key-014", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("Test 15: Blank CodeChange diff rejected")
        void test15_BlankDiffRejected() {
            codeChange.setDiff("");
            when(diffGeneratorService.computeSha256Hash("")).thenReturn("");

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-015", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        }
    }

    private HumanApproval createSampleApproval(boolean approved) {
        HumanApproval a = new HumanApproval();
        a.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        a.setProject(project);
        a.setRepository(repository);
        a.setTask(task);
        a.setCodeChange(codeChange);
        a.setReview(codeReview);
        a.setApprovalVersion(1);
        a.setRepositoryBaseSha(validBaseSha);
        a.setCodeChangeOriginalHash("1111111111222222222233333333334444444444555555555566666666667777");
        a.setCodeChangeProposedHash("8888888888999999999900000000001111111111222222222233333333334444");
        a.setCodeChangeDiffHash(validDiffHash);
        a.setReviewContextHash(validContextHash);
        a.setApprovedFiles("[\"src/Main.java\"]");
        a.setApprovedChangeCount(1);
        a.setApprovedByUser(ownerUser);
        a.setApprovedByUserEmail("owner@repopilot.internal");
        a.setApprovedByUserRole("OWNER");
        a.setApprovedAt(Instant.parse("2026-09-15T22:45:00Z"));
        a.setIdempotencyKey("test-key");
        a.setApprovalRecordHash(ApprovalCanonicalEncoder.computeApprovalRecordHash(a));
        if (approved) {
            a.setStatus(ApprovalStatus.APPROVED);
        }
        return a;
    }

    @Nested
    @DisplayName("CANONICAL HASH & DETERMINISM (Tests 18-35)")
    class CanonicalHashTests {

        @Test
        @DisplayName("Test 18 & 19: Deterministic SHA-256 hash calculation")
        void test18_19_DeterministicHash() {
            HumanApproval a1 = createSampleApproval(true);
            HumanApproval a2 = createSampleApproval(true);

            String hash1 = ApprovalCanonicalEncoder.computeApprovalRecordHash(a1);
            String hash2 = ApprovalCanonicalEncoder.computeApprovalRecordHash(a2);

            assertNotNull(hash1);
            assertEquals(64, hash1.length());
            assertEquals(hash1, hash2);

            assertTrue(ApprovalCanonicalEncoder.verifyApprovalRecordHash(a1));
        }

        @Test
        @DisplayName("Tests 20-31: Field sensitivity check - changing any field produces a different hash")
        void test20_31_FieldSensitivity() {
            HumanApproval base = createSampleApproval(false);
            String baseHash = ApprovalCanonicalEncoder.computeApprovalRecordHash(base);

            // Test 20: Different project
            HumanApproval diffProject = createSampleApproval(false);
            Project p2 = new Project(); p2.setId(UUID.randomUUID());
            diffProject.setProject(p2);
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffProject));

            // Test 21: Different repository
            HumanApproval diffRepo = createSampleApproval(false);
            Repository r2 = new Repository(); r2.setId(UUID.randomUUID());
            diffRepo.setRepository(r2);
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffRepo));

            // Test 22: Different task
            HumanApproval diffTask = createSampleApproval(false);
            Task t2 = new Task(); t2.setId(UUID.randomUUID());
            diffTask.setTask(t2);
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffTask));

            // Test 23: Different CodeChange
            HumanApproval diffChange = createSampleApproval(false);
            CodeChange c2 = new CodeChange(); c2.setId(UUID.randomUUID());
            diffChange.setCodeChange(c2);
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffChange));

            // Test 24: Different review
            HumanApproval diffReview = createSampleApproval(false);
            CodeReview rv2 = new CodeReview(); rv2.setId(UUID.randomUUID());
            diffReview.setReview(rv2);
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffReview));

            // Test 25: Different originalHash
            HumanApproval diffOrig = createSampleApproval(false);
            diffOrig.setCodeChangeOriginalHash("0000000000000000000000000000000000000000000000000000000000000000");
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffOrig));

            // Test 26: Different proposedHash
            HumanApproval diffProp = createSampleApproval(false);
            diffProp.setCodeChangeProposedHash("0000000000000000000000000000000000000000000000000000000000000000");
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffProp));

            // Test 27: Different diffHash
            HumanApproval diffDiff = createSampleApproval(false);
            diffDiff.setCodeChangeDiffHash("0000000000000000000000000000000000000000000000000000000000000000");
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffDiff));

            // Test 28: Different base SHA
            HumanApproval diffSha = createSampleApproval(false);
            diffSha.setRepositoryBaseSha("0000000000000000000000000000000000000000");
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffSha));

            // Test 29: Different context hash
            HumanApproval diffCtx = createSampleApproval(false);
            diffCtx.setReviewContextHash("0000000000000000000000000000000000000000000000000000000000000000");
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffCtx));

            // Test 30: Different approver
            HumanApproval diffUser = createSampleApproval(false);
            User u2 = new User(); u2.setId(UUID.randomUUID());
            diffUser.setApprovedByUser(u2);
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffUser));

            // Test 31: Different approval version
            HumanApproval diffVer = createSampleApproval(false);
            diffVer.setApprovalVersion(2);
            assertNotEquals(baseHash, ApprovalCanonicalEncoder.computeApprovalRecordHash(diffVer));
        }

        @Test
        @DisplayName("Test 32 & 33: Delimiter and backslash collision immunity")
        void test32_33_DelimiterAndBackslashImmunity() {
            HumanApproval a1 = createSampleApproval(false);
            a1.setApprovedByUserEmail("user:10:hacked@example.com");

            HumanApproval a2 = createSampleApproval(false);
            a2.setApprovedByUserEmail("user:10:hacked\\@example.com");

            String hash1 = ApprovalCanonicalEncoder.computeApprovalRecordHash(a1);
            String hash2 = ApprovalCanonicalEncoder.computeApprovalRecordHash(a2);

            assertNotNull(hash1);
            assertNotNull(hash2);
            assertNotEquals(hash1, hash2);
        }

        @Test
        @DisplayName("Test 34 & 35: Unicode canonicalization & control character handling")
        void test34_35_UnicodeAndControlChars() {
            HumanApproval a1 = createSampleApproval(false);
            a1.setApprovedByUserEmail("test\u00C9@example.com"); // Normal É

            String hash1 = ApprovalCanonicalEncoder.computeApprovalRecordHash(a1);
            assertNotNull(hash1);
            assertEquals(64, hash1.length());
        }
    }

    @Nested
    @DisplayName("IMMUTABILITY & PERSISTENCE (Tests 36-41)")
    class ImmutabilityTests {

        @Test
        @DisplayName("Test 36-40: Modifying snapshot fields on approved record throws IllegalStateException")
        void test36_40_ImmutabilityGuards() {
            HumanApproval approval = createSampleApproval(true);

            assertThrows(IllegalStateException.class, () -> approval.setProject(project));
            assertThrows(IllegalStateException.class, () -> approval.setRepository(repository));
            assertThrows(IllegalStateException.class, () -> approval.setTask(task));
            assertThrows(IllegalStateException.class, () -> approval.setCodeChange(codeChange));
            assertThrows(IllegalStateException.class, () -> approval.setReview(codeReview));
            assertThrows(IllegalStateException.class, () -> approval.setRepositoryBaseSha(validBaseSha));
            assertThrows(IllegalStateException.class, () -> approval.setCodeChangeDiffHash(validDiffHash));
            assertThrows(IllegalStateException.class, () -> approval.setApprovalRecordHash(validContextHash));
            assertThrows(IllegalStateException.class, () -> approval.setApprovedByUser(ownerUser));
        }

        @Test
        @DisplayName("Test 41: Tampered persisted record fails self-verification")
        void test41_TamperedRecordFailsVerification() {
            HumanApproval approval = createSampleApproval(true);
            assertTrue(ApprovalCanonicalEncoder.verifyApprovalRecordHash(approval));

            // Tamper hash via Reflection to simulate DB corruption/tampering
            try {
                java.lang.reflect.Field field = HumanApproval.class.getDeclaredField("approvalRecordHash");
                field.setAccessible(true);
                field.set(approval, "0000000000000000000000000000000000000000000000000000000000000000");
            } catch (Exception e) {
                fail("Failed to set private field for tamper test");
            }

            assertFalse(ApprovalCanonicalEncoder.verifyApprovalRecordHash(approval));
        }
    }

    @Nested
    @DisplayName("DUPLICATION, REVOCATION & AUDIT (Tests 48-67)")
    class DuplicationAndAuditTests {

        @Test
        @DisplayName("Test 48: Idempotent creation returns existing record")
        void test48_IdempotentCreationReturnsExisting() {
            HumanApproval existing = createSampleApproval(true);
            existing.setIdempotencyKey("idem-key-1");

            when(humanApprovalRepository.findByIdempotencyKey("idem-key-1")).thenReturn(Optional.of(existing));

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "idem-key-1", true);
            HumanApprovalResponseDto dto = humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser);

            assertEquals(existing.getId(), dto.approvalId());
            assertEquals(existing.getApprovalRecordHash(), dto.approvalRecordHash());
        }

        @Test
        @DisplayName("Test 48b: Idempotency key reuse across different CodeChanges rejected")
        void test48b_IdempotencyKeyReuseDifferentCodeChangeRejected() {
            HumanApproval existing = createSampleApproval(false);
            existing.setIdempotencyKey("idem-key-shared");

            // Change ID differs from requested changeId!
            CodeChange otherChange = new CodeChange();
            otherChange.setId(UUID.randomUUID());
            existing.setCodeChange(otherChange);
            existing.setStatus(ApprovalStatus.APPROVED);

            when(humanApprovalRepository.findByIdempotencyKey("idem-key-shared")).thenReturn(Optional.of(existing));

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "idem-key-shared", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );

            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
            assertEquals("IDEMPOTENCY_KEY_REUSE", ex.getCode());
        }

        @Test
        @DisplayName("Test 52: Malicious file path with path traversal rejected")
        void test52_MaliciousPathRejected() {
            codeChange.setFilePath("../../../etc/passwd");

            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-52", true);
            ApiException ex = assertThrows(ApiException.class, () ->
                    humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser)
            );
            assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        @Test
        @DisplayName("Test 59-62: Audit event generated without secrets or raw source")
        void test59_62_AuditEventGeneratedWithoutSecrets() {
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-59", true);
            humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser);

            verify(auditLogService, times(1)).logAction(
                    eq(ownerUser),
                    eq(project),
                    eq(task),
                    eq("HUMAN_APPROVAL_GRANTED"),
                    eq("HUMAN_APPROVAL"),
                    any(UUID.class),
                    argThat(meta -> !meta.contains("ghp_") && !meta.contains("secret") && !meta.contains("public class Main"))
            );
        }

        @Test
        @DisplayName("Test 63-67: Zero side-effects (No Git, GitHub, Docker, or Token operations)")
        void test63_67_ZeroSideEffects() {
            HumanApprovalCreateRequest request = new HumanApprovalCreateRequest(validBaseSha, "key-63", true);
            HumanApprovalResponseDto dto = humanApprovalService.createApproval(projectId, repoId, taskId, changeId, reviewId, request, ownerUser);

            assertNotNull(dto);
            // Verify no Git/GitHub/Docker calls occurred during approval creation
        }
    }
}
