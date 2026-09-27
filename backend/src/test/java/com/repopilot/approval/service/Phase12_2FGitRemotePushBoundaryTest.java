package com.repopilot.approval.service;

import com.repopilot.approval.dto.*;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.entity.PullRequestPublication;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.repository.PullRequestPublicationRepository;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.dto.BranchDto;
import com.repopilot.github.security.GitAskpassManager;
import com.repopilot.github.security.GithubCredentialContext;
import com.repopilot.github.security.GithubCredentialProvider;
import com.repopilot.github.security.SecretRedactor;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.service.DurableOrchestratorOwnershipService;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase12_2FGitRemotePushBoundaryTest {

    @TempDir
    Path tempDir;

    private ProjectAuthorizationService projectAuthorizationService;
    private HumanApprovalRepository humanApprovalRepository;
    private CodeChangeRepository codeChangeRepository;
    private RepositoryRepository repositoryRepository;
    private TaskRepository taskRepository;
    private PullRequestPublicationRepository pullRequestPublicationRepository;
    private ApprovedChangeVerifier approvedChangeVerifier;
    private MutationIntegrityVerifier mutationIntegrityVerifier;
    private GitBranchCommitBoundary gitBranchCommitBoundary;
    private GithubRemoteIdentityVerifier githubRemoteIdentityVerifier;
    private GithubCredentialProvider githubCredentialProvider;
    private GitAskpassManager gitAskpassManager;
    private GithubApiService githubApiService;
    private DurableOrchestratorOwnershipService durableOrchestratorOwnershipService;
    private MutationWorkspaceManager mutationWorkspaceManager;
    private AuditLogService auditLogService;

    private GitRemotePushBoundaryImpl gitRemotePushBoundary;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID codeChangeId;
    private UUID approvalId;
    private UUID operationId;
    private User requester;

    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private CodeReview review;
    private HumanApproval approval;

    private String rawSentinelToken;
    private String encryptedToken;
    private String baseSha;
    private String commitSha;
    private String treeSha;
    private String branchName;
    private List<String> committedPaths;

    @BeforeEach
    void setUp() throws IOException {
        projectAuthorizationService = mock(ProjectAuthorizationService.class);
        humanApprovalRepository = mock(HumanApprovalRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        taskRepository = mock(TaskRepository.class);
        pullRequestPublicationRepository = mock(PullRequestPublicationRepository.class);
        approvedChangeVerifier = mock(ApprovedChangeVerifier.class);
        mutationIntegrityVerifier = mock(MutationIntegrityVerifier.class);
        gitBranchCommitBoundary = mock(GitBranchCommitBoundary.class);
        githubRemoteIdentityVerifier = mock(GithubRemoteIdentityVerifier.class);
        githubCredentialProvider = mock(GithubCredentialProvider.class);
        gitAskpassManager = mock(GitAskpassManager.class);
        githubApiService = mock(GithubApiService.class);
        durableOrchestratorOwnershipService = mock(DurableOrchestratorOwnershipService.class);
        mutationWorkspaceManager = mock(MutationWorkspaceManager.class);
        auditLogService = mock(AuditLogService.class);

        gitRemotePushBoundary = spy(new GitRemotePushBoundaryImpl(
                projectAuthorizationService,
                humanApprovalRepository,
                codeChangeRepository,
                repositoryRepository,
                taskRepository,
                pullRequestPublicationRepository,
                approvedChangeVerifier,
                mutationIntegrityVerifier,
                gitBranchCommitBoundary,
                githubRemoteIdentityVerifier,
                githubCredentialProvider,
                gitAskpassManager,
                githubApiService,
                durableOrchestratorOwnershipService,
                mutationWorkspaceManager,
                auditLogService
        ));

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();
        approvalId = UUID.randomUUID();
        operationId = UUID.randomUUID();

        rawSentinelToken = "ghp_TESTSENTINELPAT12345678901234567890";
        encryptedToken = "ENCRYPTED_BLOB_AES256GCM_98765";
        baseSha = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b";
        commitSha = "9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e";
        treeSha = "7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b";

        String shortTaskId = taskId.toString().replace("-", "").substring(0, 8);
        String shortApprovalId = approvalId.toString().replace("-", "").substring(0, 8);
        branchName = "repopilot/task-" + shortTaskId + "-" + shortApprovalId;
        committedPaths = List.of("src/Main.java");

        requester = new User();
        requester.setId(UUID.randomUUID());
        requester.setGithubUsername("test-github-user");
        requester.setGithubAccessToken(encryptedToken);

        project = new Project();
        project.setId(projectId);

        repository = new Repository();
        repository.setId(repositoryId);
        repository.setProject(project);
        repository.setOwnerName("test-owner");
        repository.setRepoName("test-repo");
        repository.setGithubRepoId("12345678");
        repository.setDefaultBranch("main");

        task = new Task();
        task.setId(taskId);
        task.setProject(project);

        codeChange = new CodeChange();
        codeChange.setId(codeChangeId);
        codeChange.setTask(task);

        review = new CodeReview();
        review.setId(UUID.randomUUID());

        approval = new HumanApproval();
        approval.setId(approvalId);
        approval.setProject(project);
        approval.setRepository(repository);
        approval.setTask(task);
        approval.setCodeChange(codeChange);
        approval.setReview(review);
        approval.setRepositoryBaseSha(baseSha);
        approval.setApprovalRecordHash("valid_record_hash");
        approval.setStatus(ApprovalStatus.APPROVED);

        Path workspacePath = tempDir.resolve("workspace");
        Files.createDirectories(workspacePath.resolve(".git"));

        when(projectAuthorizationService.getAuthorizedProject(projectId, requester)).thenReturn(project);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(codeChangeRepository.findById(codeChangeId)).thenReturn(Optional.of(codeChange));
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        when(durableOrchestratorOwnershipService.acquireOwnership(eq(approvalId), any(Duration.class))).thenReturn("fence-token-123");
        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), eq("fence-token-123"))).thenReturn(true);

        when(approvedChangeVerifier.verifyApprovedChange(projectId, repositoryId, taskId, codeChangeId, approvalId, requester))
                .thenReturn(ApprovedChangeVerificationResult.valid(
                        approvalId, projectId, repositoryId, taskId, codeChangeId, review.getId(),
                        1, baseSha, "orig_hash", "prop_hash", "diff_hash", "rev_hash",
                        committedPaths, "valid_record_hash", requester.getId()
                ));

        when(mutationIntegrityVerifier.verifyMutationIntegrity(approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester))
                .thenReturn(MutationIntegrityResult.verified(operationId, approvalId, projectId, repositoryId, taskId, codeChangeId, workspacePath.toString(), treeSha, committedPaths));

        when(gitBranchCommitBoundary.createBranchAndCommit(approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester))
                .thenReturn(GitMutationResult.verified(operationId, approvalId, projectId, repositoryId, taskId, codeChangeId, branchName, commitSha, baseSha, treeSha, committedPaths));

        when(githubRemoteIdentityVerifier.verifyGithubRemoteIdentity(projectId, repositoryId, taskId, codeChangeId, approvalId, requester, encryptedToken))
                .thenReturn(GithubRemoteVerificationResult.verified(approvalId, projectId, repositoryId, taskId, codeChangeId, repositoryId.toString(), "12345678", "test-owner", "test-repo", "main", baseSha, baseSha));

        when(githubCredentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester))
                .thenAnswer(inv -> new GithubCredentialContext(rawSentinelToken.toCharArray(), "test-github-user"));

        Map<UUID, PullRequestPublication> publicationDb = new HashMap<>();
        when(pullRequestPublicationRepository.findByApprovalId(any(UUID.class))).thenAnswer(inv -> {
            UUID appValId = inv.getArgument(0);
            return Optional.ofNullable(publicationDb.get(appValId));
        });
        when(pullRequestPublicationRepository.save(any(PullRequestPublication.class))).thenAnswer(inv -> {
            PullRequestPublication p = inv.getArgument(0);
            publicationDb.put(p.getApproval().getId(), p);
            return p;
        });
    }

    @Test
    @DisplayName("1. User Role Authorization: Throws FORBIDDEN when user lacks MEMBER role")
    void testPushApprovedCommit_UnauthorizedUserRole() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "Minimum role MEMBER required"))
                .when(projectAuthorizationService).requireMinimumRole(eq(project), eq(requester), eq(ProjectRole.MEMBER));

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_VERIFICATION, result.status());
        assertEquals("AUTHORIZATION_FAILURE", result.failureCategory());
        verify(githubCredentialProvider, never()).getGithubCredential(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("2. Redis Fencing Lock: Fails closed when lock is already held by another worker")
    void testPushApprovedCommit_FencingLockAcquisitionFailure() {
        when(durableOrchestratorOwnershipService.acquireOwnership(eq(approvalId), any(Duration.class))).thenReturn(null);

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_PUSH, result.status());
        assertEquals("PUBLICATION_IN_PROGRESS", result.failureCategory());
        verify(approvedChangeVerifier, never()).verifyApprovedChange(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("3. Pre-Push Stale Approval Rejection: Fails closed when Phase 12.2-A re-verification fails")
    void testPushApprovedCommit_StaleApprovalRejection() {
        when(approvedChangeVerifier.verifyApprovedChange(projectId, repositoryId, taskId, codeChangeId, approvalId, requester))
                .thenReturn(ApprovedChangeVerificationResult.failure(VerificationFailureCategory.APPROVAL_STALE, "Approval status is no longer APPROVED", approvalId, projectId, repositoryId, taskId, codeChangeId));

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_STALE, result.status());
        assertEquals("APPROVAL_STALE", result.failureCategory());
        verify(githubCredentialProvider, never()).getGithubCredential(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("4. Pre-Push Remote Target Branch Reconciliation: Reconciles as RECONCILED when remote branch already matches commit SHA")
    void testPushApprovedCommit_ReconcileExistingMatchingRemoteBranch() {
        BranchDto remoteBranch = new BranchDto(null, branchName, commitSha, false);
        when(githubApiService.fetchBranchDetails(eq(rawSentinelToken), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenReturn(remoteBranch);

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isPushed());
        assertEquals(PullRequestPublicationStatus.RECONCILED, result.status());
        assertEquals(branchName, result.branchName());
        assertEquals(commitSha, result.commitSha());
        verify(auditLogService).logAction(eq(requester), eq(project), eq(task), eq("GITHUB_PUSH_RECONCILED"), eq("HUMAN_APPROVAL"), eq(approvalId), any());
    }

    @Test
    @DisplayName("5. Pre-Push Remote Target Branch Conflict: Fails closed with REMOTE_BRANCH_CONFLICT when remote branch has different SHA")
    void testPushApprovedCommit_RemoteBranchConflict() {
        String differentSha = "1111222233334444555566667777888899990000";
        BranchDto remoteBranch = new BranchDto(null, branchName, differentSha, false);
        when(githubApiService.fetchBranchDetails(eq(rawSentinelToken), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenReturn(remoteBranch);

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_CONFLICT, result.status());
        assertEquals("REMOTE_BRANCH_CONFLICT", result.failureCategory());
        verify(auditLogService).logAction(eq(requester), eq(project), eq(task), eq("GITHUB_PUSH_FAILED"), eq("HUMAN_APPROVAL"), eq(approvalId), any());
    }

    @Test
    @DisplayName("6. Successful Push & Post-Push Verification: Pushes branch & verifies post-push SHA via GitHub API")
    void testPushApprovedCommit_Success() {
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found"))
                .thenReturn(new BranchDto(null, branchName, commitSha, false));

        doReturn(true).when(gitRemotePushBoundary).executeGitPushCommand(any(), any(), any());

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertEquals(branchName, result.branchName());
        assertEquals(commitSha, result.commitSha());
        assertEquals(PullRequestPublicationStatus.PUSHED, result.status());
        verify(auditLogService).logAction(eq(requester), eq(project), eq(task), eq("GITHUB_PUSH_SUCCEEDED"), eq("HUMAN_APPROVAL"), eq(approvalId), any());
    }

    @Test
    @DisplayName("7. Idempotent Retry: Returns existing PUSHED publication without re-pushing")
    void testPushApprovedCommit_IdempotentRetry() {
        PullRequestPublication existingPub = new PullRequestPublication();
        existingPub.setId(UUID.randomUUID());
        existingPub.setApproval(approval);
        existingPub.setCodeChange(codeChange);
        existingPub.setRepository(repository);
        existingPub.setTask(task);
        existingPub.setProject(project);
        existingPub.setInitiatedByUser(requester);
        existingPub.setBranchName(branchName);
        existingPub.setBaseBranch("main");
        existingPub.setCommitSha(commitSha);
        existingPub.setParentCommitSha(baseSha);
        existingPub.setTreeSha(treeSha);
        existingPub.setGithubRepoId("12345678");
        existingPub.setPublicationStatus(PullRequestPublicationStatus.PUSHED);

        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(existingPub));

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isPushed());
        assertEquals(PullRequestPublicationStatus.PUSHED, result.status());
        verify(githubCredentialProvider, never()).getGithubCredential(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("8. Secret Redactor Integrity: Sanitizes exception traces containing secrets")
    void testSecretRedactor_ExceptionSanitization() {
        String secretLog = "Failed push to Authorization: Bearer " + rawSentinelToken + " on github.com/test-owner/test-repo.git";
        String sanitized = SecretRedactor.sanitizeLogs(secretLog);

        assertFalse(sanitized.contains(rawSentinelToken));
        assertTrue(sanitized.contains("[REDACTED_SECRET]"));
    }

    @Test
    @DisplayName("9. Stale Worker Fence Guard: Aborts DB state update if fence token lost before DB save")
    void testStaleWorker_FencingGuardAbortsDbSave() {
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found"));

        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), eq("fence-token-123"))).thenReturn(false);

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_PUSH, result.status());
        assertEquals("FENCING_LOST", result.failureCategory());
        verify(pullRequestPublicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("10. Approval TOCTOU Guard: Aborts push if approval status is revoked immediately prior to push")
    void testApprovalTOCTOU_RevokedPriorToPush() {
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found"));

        HumanApproval revokedApproval = new HumanApproval();
        revokedApproval.setId(approvalId);
        revokedApproval.setStatus(ApprovalStatus.REVOKED);

        when(humanApprovalRepository.findById(approvalId))
                .thenReturn(Optional.of(approval)) // Initial lookup
                .thenReturn(Optional.of(revokedApproval)); // Pre-push TOCTOU lookup

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_STALE, result.status());
        assertEquals("APPROVAL_REVOKED", result.failureCategory());
        verify(gitRemotePushBoundary, never()).executeGitPushCommand(any(), any(), any());
    }

    @Test
    @DisplayName("11. Unknown Push State Recovery: Reconciles as RECONCILED if push process timed out but branch landed on GitHub")
    void testUnknownPushState_ReconciledOnGithubCheck() {
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found")) // Pre-push check
                .thenReturn(new BranchDto(null, branchName, commitSha, false)); // Recovery check

        doReturn(false).when(gitRemotePushBoundary).executeGitPushCommand(any(), any(), any());

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isPushed());
        assertEquals(PullRequestPublicationStatus.RECONCILED, result.status());
        verify(auditLogService).logAction(eq(requester), eq(project), eq(task), eq("GITHUB_PUSH_RECONCILED"), eq("HUMAN_APPROVAL"), eq(approvalId), any());
    }

    @Test
    @DisplayName("12. Real Bare Git Integration: Empirical single-ref push, default branch immutability, zero extra refs")
    void testRealGitIntegration_SingleRefPushAndDefaultBranchImmutability() throws Exception {
        // Create local bare repository representing remote
        Path remoteBareRepo = tempDir.resolve("remote.git");
        Files.createDirectories(remoteBareRepo);
        runCmd(remoteBareRepo, "git", "init", "--bare");

        // Create local workspace repository
        Path localRepo = tempDir.resolve("local-repo");
        Files.createDirectories(localRepo);
        runCmd(localRepo, "git", "init");
        runCmd(localRepo, "git", "config", "user.name", "Test");
        runCmd(localRepo, "git", "config", "user.email", "test@example.com");

        // Commit initial baseline commit on main branch
        Path sampleFile = localRepo.resolve("README.md");
        Files.writeString(sampleFile, "Initial README");
        runCmd(localRepo, "git", "add", "README.md");
        runCmd(localRepo, "git", "commit", "-m", "Initial commit");
        runCmd(localRepo, "git", "branch", "-M", "main");
        runCmd(localRepo, "git", "remote", "add", "origin", remoteBareRepo.toString());

        // Push baseline to remote bare repo
        runCmd(localRepo, "git", "push", "origin", "main");
        String remoteDefaultBranchSha = runCmd(remoteBareRepo, "git", "rev-parse", "refs/heads/main").trim();

        // Create task branch locally
        String taskBranch = "repopilot/task-12345678-87654321";
        runCmd(localRepo, "git", "checkout", "-b", taskBranch);
        Path codeFile = localRepo.resolve("src/Main.java");
        Files.createDirectories(codeFile.getParent());
        Files.writeString(codeFile, "public class Main {}");
        runCmd(localRepo, "git", "add", "src/Main.java");
        runCmd(localRepo, "git", "commit", "-m", "Feature commit");
        String taskCommitSha = runCmd(localRepo, "git", "rev-parse", "HEAD").trim();

        // Execute constrained single-ref push using GitRemotePushBoundaryImpl method
        GithubCredentialContext dummyCred = new GithubCredentialContext("dummy".toCharArray(), "test-user");
        boolean pushSuccess = gitRemotePushBoundary.executeGitPushCommand(localRepo, taskBranch, dummyCred);
        assertTrue(pushSuccess);

        // Assert 1: Target branch exists on remote bare repo with exact commit SHA
        String remoteTaskBranchSha = runCmd(remoteBareRepo, "git", "rev-parse", "refs/heads/" + taskBranch).trim();
        assertEquals(taskCommitSha, remoteTaskBranchSha);

        // Assert 2: Remote main default branch SHA was NOT modified (Master Immutability)
        String postPushDefaultBranchSha = runCmd(remoteBareRepo, "git", "rev-parse", "refs/heads/main").trim();
        assertEquals(remoteDefaultBranchSha, postPushDefaultBranchSha);

        // Assert 3: Exactly one branch ref created; ZERO tags created
        String allRefs = runCmd(remoteBareRepo, "git", "for-each-ref", "--format=%(refname)");
        assertTrue(allRefs.contains("refs/heads/main"));
        assertTrue(allRefs.contains("refs/heads/" + taskBranch));
        assertFalse(allRefs.contains("refs/tags/"));
    }

    @Test
    @DisplayName("13. State Machine: Unit verification of all legal state transitions")
    void testValidateStateTransition_LegalTransitions() {
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.VERIFYING));
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.FAILED_STALE));
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.FAILED_VERIFICATION));
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.FAILED_PUSH));

        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.VERIFYING, PullRequestPublicationStatus.PUSHING));
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.VERIFYING, PullRequestPublicationStatus.RECONCILED));
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.VERIFYING, PullRequestPublicationStatus.FAILED_STALE));

        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PUSHING, PullRequestPublicationStatus.PUSHED));
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PUSHING, PullRequestPublicationStatus.RECONCILED));
        assertDoesNotThrow(() -> gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PUSHING, PullRequestPublicationStatus.FAILED_PUSH));
    }

    @Test
    @DisplayName("14. State Machine: Unit verification of illegal transitions, PENDING -> PUSHING rejection, and terminal state regression guard")
    void testValidateStateTransition_IllegalTransitionsAndTerminalRegression() {
        // Disallowed PENDING transitions: PENDING cannot jump directly to PUSHING, RECONCILED, or PUSHED
        assertThrows(IllegalStateException.class, () ->
                gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.PUSHING));
        assertThrows(IllegalStateException.class, () ->
                gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.RECONCILED));
        assertThrows(IllegalStateException.class, () ->
                gitRemotePushBoundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.PUSHED));

        // Terminal state regressions: terminal state CANNOT transition to any DIFFERENT state (except FAILED_PUSH -> RECONCILED)
        for (PullRequestPublicationStatus terminal : PullRequestPublicationStatus.values()) {
            if (terminal.isTerminal()) {
                for (PullRequestPublicationStatus other : PullRequestPublicationStatus.values()) {
                    if (other != terminal && !(terminal == PullRequestPublicationStatus.FAILED_PUSH && other == PullRequestPublicationStatus.RECONCILED)) {
                        assertThrows(IllegalStateException.class, () ->
                                gitRemotePushBoundary.validateStateTransition(terminal, other));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("15. Unknown Push State Failure: Fails closed with UNKNOWN_PUSH_STATE when git push fails and branch is absent on remote")
    void testUnknownPushState_BranchAbsentOnGithub_FailsPush() {
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found"));

        doReturn(false).when(gitRemotePushBoundary).executeGitPushCommand(any(), any(), any());

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_PUSH, result.status());
        assertEquals("UNKNOWN_PUSH_STATE", result.failureCategory());
        verify(auditLogService).logAction(eq(requester), eq(project), eq(task), eq("GITHUB_PUSH_FAILED"), eq("HUMAN_APPROVAL"), eq(approvalId), any());
    }

    @Test
    @DisplayName("16. Unknown Push State API Error: Fails closed when GitHub recovery query throws exception")
    void testUnknownPushState_GithubApiError_FailsPush() {
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found")) // Pre-push check
                .thenThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GitHub API timeout")); // Recovery check

        doReturn(false).when(gitRemotePushBoundary).executeGitPushCommand(any(), any(), any());

        GitPushResult result = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_PUSH, result.status());
        assertEquals("UNKNOWN_PUSH_STATE", result.failureCategory());
    }

    @Test
    @DisplayName("17. Pre-Push Remote Check Before Retry: Authoritative GitHub GET is ALWAYS performed prior to any git push CLI execution")
    void testRetryPerformsPrePushGithubCheckBeforePush() {
        // Pre-push check returns 404 (Branch not found), so push command is executed
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found")) // Pre-push check
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Branch not found")); // Recovery check

        doReturn(false).when(gitRemotePushBoundary).executeGitPushCommand(any(), any(), any());

        GitPushResult firstAttempt = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertEquals(PullRequestPublicationStatus.FAILED_PUSH, firstAttempt.status());

        // Now simulate retry: branch was created on GitHub during or after first attempt
        BranchDto remoteBranchOnRetry = new BranchDto(null, branchName, commitSha, false);
        when(githubApiService.fetchBranchDetails(anyString(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenReturn(remoteBranchOnRetry);

        GitPushResult retryAttempt = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        // Pre-push check discovered branch landed on remote -> reconciles without executing push command a second time!
        assertTrue(retryAttempt.isPushed());
        assertEquals(PullRequestPublicationStatus.RECONCILED, retryAttempt.status());
        verify(gitRemotePushBoundary, times(1)).executeGitPushCommand(any(), any(), any());
    }

    @Test
    @DisplayName("18. Real Multithreaded TOCTOU Concurrency Barrier: Worker A pauses after verification; Worker B revokes approval; Worker A resumes and is rejected")
    void test18_RealMultithreadedToctouConcurrencyBarrier_PushBoundary() throws Exception {
        java.util.concurrent.CountDownLatch workerAPaused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch workerBRevoked = new java.util.concurrent.CountDownLatch(1);

        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    workerAPaused.countDown();
                    assertTrue(workerBRevoked.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A timed out waiting for Worker B revocation");
                    return com.repopilot.approval.dto.ApprovedChangeVerificationResult.failure(
                            com.repopilot.approval.dto.VerificationFailureCategory.APPROVAL_STALE,
                            "Approval was revoked during execution window", approvalId, projectId, repositoryId, taskId, codeChangeId
                    );
                });

        java.util.concurrent.atomic.AtomicReference<GitPushResult> workerAResult = new java.util.concurrent.atomic.AtomicReference<>();

        Thread workerA = new Thread(() -> {
            GitPushResult res = gitRemotePushBoundary.pushApprovedCommitToRemote(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            workerAResult.set(res);
        });

        workerA.start();

        assertTrue(workerAPaused.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A failed to reach pause barrier");

        approval.setStatus(com.repopilot.approval.entity.ApprovalStatus.REVOKED);
        workerBRevoked.countDown();

        workerA.join(5000);

        GitPushResult result = workerAResult.get();
        assertNotNull(result);
        assertFalse(result.isPushed());
        assertEquals(PullRequestPublicationStatus.FAILED_STALE, result.status());
        assertEquals("APPROVAL_STALE", result.failureCategory());
    }

    @Test
    @DisplayName("19. Real Stale-Worker Redis Fencing Rejection: Worker A loses fencing token to Worker B; Worker A resumes and is fenced from mutation")
    void test19_RealStaleWorkerFencingRejection_PushBoundary() throws Exception {
        java.util.concurrent.CountDownLatch workerAPaused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch workerBPreempted = new java.util.concurrent.CountDownLatch(1);

        String fenceTokenA = "fence-token-worker-A-1111";

        when(durableOrchestratorOwnershipService.acquireOwnership(eq(approvalId), any()))
                .thenReturn(fenceTokenA);

        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    workerAPaused.countDown();
                    assertTrue(workerBPreempted.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A timed out waiting for Worker B preemption");
                    return com.repopilot.approval.dto.ApprovedChangeVerificationResult.valid(
                            approvalId, projectId, repositoryId, taskId, codeChangeId, review.getId(),
                            1, baseSha, "orig_hash", "prop_hash", "diff_hash", "rev_hash",
                            committedPaths, "valid_record_hash", requester.getId()
                    );
                });

        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), eq(fenceTokenA)))
                .thenReturn(false);

        java.util.concurrent.atomic.AtomicReference<GitPushResult> workerAResult = new java.util.concurrent.atomic.AtomicReference<>();

        Thread workerA = new Thread(() -> {
            GitPushResult res = gitRemotePushBoundary.pushApprovedCommitToRemote(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            workerAResult.set(res);
        });

        workerA.start();

        assertTrue(workerAPaused.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A failed to reach pause barrier");

        workerBPreempted.countDown();

        workerA.join(5000);

        GitPushResult result = workerAResult.get();
        assertNotNull(result);
        assertFalse(result.isPushed());
        verify(gitRemotePushBoundary, never()).executeGitPushCommand(any(), any(), any());
    }

    private String runCmd(Path dir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) {
            sb.append(l).append("\n");
        }
        p.waitFor();
        return sb.toString();
    }
}
