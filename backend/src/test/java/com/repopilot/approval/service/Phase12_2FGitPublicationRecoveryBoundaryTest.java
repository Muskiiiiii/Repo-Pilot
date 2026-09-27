package com.repopilot.approval.service;

import com.repopilot.approval.dto.GitPullRequestResult;
import com.repopilot.approval.dto.GitPushResult;
import com.repopilot.approval.dto.PullRequestPublicationStatus;
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
import com.repopilot.github.dto.GithubPullRequestDto;
import com.repopilot.github.security.GithubCredentialContext;
import com.repopilot.github.security.GithubCredentialProvider;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.service.DurableOrchestratorOwnershipService;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class Phase12_2FGitPublicationRecoveryBoundaryTest {

    @Mock
    private PullRequestPublicationRepository pullRequestPublicationRepository;

    @Mock
    private HumanApprovalRepository humanApprovalRepository;

    @Mock
    private CodeChangeRepository codeChangeRepository;

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectAuthorizationService projectAuthorizationService;

    @Mock
    private GithubCredentialProvider githubCredentialProvider;

    @Mock
    private GithubApiService githubApiService;

    @Mock
    private GithubRemoteIdentityVerifier githubRemoteIdentityVerifier;

    @Mock
    private GitRemotePushBoundary gitRemotePushBoundary;

    @Mock
    private GitPullRequestCreationBoundary gitPullRequestCreationBoundary;

    @Mock
    private DurableOrchestratorOwnershipService durableOrchestratorOwnershipService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private GitPublicationRecoveryBoundaryImpl boundary;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID codeChangeId;
    private UUID approvalId;

    private User user;
    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private HumanApproval approval;
    private PullRequestPublication publication;

    private String fenceToken;
    private String branchName;
    private String commitSha;
    private String baseBranch;
    private String githubRepoId;

    @BeforeEach
    void setUp() {
        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();
        approvalId = UUID.randomUUID();

        fenceToken = "FENCE_TOKEN_" + UUID.randomUUID();
        branchName = "repopilot/task-test-branch";
        commitSha = "9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e";
        baseBranch = "main";
        githubRepoId = "12345678";

        user = new User();
        user.setId(UUID.randomUUID());
        user.setName("Test Developer");
        user.setEmail("dev@example.com");

        project = new Project();
        project.setId(projectId);
        project.setName("Test Project");

        repository = new Repository();
        repository.setId(repositoryId);
        repository.setProject(project);
        repository.setOwnerName("test-owner");
        repository.setRepoName("test-repo");
        repository.setGithubRepoId(githubRepoId);
        repository.setDefaultBranch(baseBranch);

        task = new Task();
        task.setId(taskId);
        task.setProject(project);

        codeChange = new CodeChange();
        codeChange.setId(codeChangeId);
        codeChange.setTask(task);

        approval = new HumanApproval();
        approval.setId(approvalId);
        approval.setProject(project);
        approval.setRepository(repository);
        approval.setTask(task);
        approval.setCodeChange(codeChange);
        approval.setRepositoryBaseSha("0000000000000000000000000000000000000000");
        approval.setStatus(ApprovalStatus.APPROVED);

        publication = new PullRequestPublication();
        publication.setId(UUID.randomUUID());
        publication.setApproval(approval);
        publication.setProject(project);
        publication.setRepository(repository);
        publication.setTask(task);
        publication.setCodeChange(codeChange);
        publication.setInitiatedByUser(user);
        publication.setBranchName(branchName);
        publication.setBaseBranch(baseBranch);
        publication.setCommitSha(commitSha);
        publication.setParentCommitSha("0000000000000000000000000000000000000000");
        publication.setTreeSha("1111111111111111111111111111111111111111");
        publication.setGithubRepoId(githubRepoId);
        publication.setPublicationStatus(PullRequestPublicationStatus.PR_CREATED);
        publication.setGithubPrNumber(42L);
        publication.setPrUrl("https://github.com/test-owner/test-repo/pull/42");

        when(projectAuthorizationService.getAuthorizedProject(eq(projectId), eq(user)))
                .thenReturn(project);
        doNothing().when(projectAuthorizationService)
                .requireMinimumRole(eq(project), eq(user), eq(ProjectRole.MEMBER));

        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(durableOrchestratorOwnershipService.acquireOwnership(eq(approvalId), any(Duration.class)))
                .thenReturn(fenceToken);
        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), eq(fenceToken)))
                .thenReturn(true);

        GithubCredentialContext credContext = new GithubCredentialContext("ghp_test_token".toCharArray(), "test-github-user");
        when(githubCredentialProvider.getGithubCredential(any(), any(), any(), any(), any()))
                .thenReturn(credContext);
    }

    @Test
    @DisplayName("1. Authorization Failure: Insufficient role returns fail-closed result")
    void testAuthorization_InsufficientRole_FailsClosed() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "Access denied"))
                .when(projectAuthorizationService).requireMinimumRole(eq(project), eq(user), eq(ProjectRole.MEMBER));

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_VERIFICATION, result.status());
        assertEquals("AUTHORIZATION_FAILURE", result.failureCategory());
        verify(pullRequestPublicationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("2. Approval Revoked: Approval status != APPROVED returns FAILED_STALE")
    void testPrecondition_ApprovalRevoked_FailsClosed() {
        approval.setStatus(ApprovalStatus.REVOKED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_STALE, result.status());
        assertEquals("APPROVAL_REVOKED", result.failureCategory());
    }

    @Test
    @DisplayName("3. Fresh Publication Workflow: Publication missing triggers fresh F-C push and F-D PR creation")
    void testPrecondition_PublicationMissing_ExecutesFreshWorkflow() {
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.empty());

        GitPushResult pushResult = GitPushResult.success(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                branchName, baseBranch, commitSha, "parentSha", "treeSha", githubRepoId, List.of("src/Main.java"), PullRequestPublicationStatus.PUSHED
        );
        when(gitRemotePushBoundary.pushApprovedCommitToRemote(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user)))
                .thenReturn(pushResult);

        GitPullRequestResult expectedPrResult = GitPullRequestResult.success(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                100L, "https://github.com/test-owner/test-repo/pull/100", branchName, baseBranch, commitSha, "Pull Request #100", PullRequestPublicationStatus.PR_CREATED
        );
        when(gitPullRequestCreationBoundary.createPullRequestFromPublication(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user)))
                .thenReturn(expectedPrResult);

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertTrue(result.isCreated());
        assertEquals(100L, result.githubPrNumber());
        verify(gitRemotePushBoundary, times(1)).pushApprovedCommitToRemote(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user));
        verify(gitPullRequestCreationBoundary, times(1)).createPullRequestFromPublication(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user));
    }

    @Test
    @DisplayName("4. Idempotency: Already PR_CREATED verifies GitHub state authoritatively and returns without recreating")
    void testIdempotency_AlreadyPrCreated_ReturnsSuccessWithoutRecreating() {
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        GithubPullRequestDto prDto = createValidGithubPrDto(42L, branchName, commitSha, baseBranch, githubRepoId, "test-owner", "test-repo");
        when(githubApiService.findPullRequestsByHead(any(), eq("test-owner"), eq("test-repo"), eq(branchName), eq(baseBranch)))
                .thenReturn(List.of(prDto));

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertTrue(result.isCreated());
        assertEquals(42L, result.githubPrNumber());
        assertEquals("https://github.com/test-owner/test-repo/pull/42", result.prUrl());
        verify(gitPullRequestCreationBoundary, never()).createPullRequestFromPublication(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("5. Crash Recovery: Push Completed (PUSHED state) executes PR creation boundary directly")
    void testCrashRecovery_PushCompleted_PrPending_ExecutesPrCreation() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PUSHED);
        publication.setGithubPrNumber(null);
        publication.setPrUrl(null);
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        BranchDto branchDto = new BranchDto();
        branchDto.setName(branchName);
        branchDto.setCommitSha(commitSha);
        when(githubApiService.fetchBranchDetails(any(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenReturn(branchDto);

        GitPullRequestResult expectedPrResult = GitPullRequestResult.success(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                55L, "https://github.com/test-owner/test-repo/pull/55", branchName, baseBranch, commitSha, "Pull Request #55", PullRequestPublicationStatus.PR_CREATED
        );
        when(gitPullRequestCreationBoundary.createPullRequestFromPublication(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user)))
                .thenReturn(expectedPrResult);

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertTrue(result.isCreated());
        assertEquals(55L, result.githubPrNumber());
        verify(gitPullRequestCreationBoundary, times(1)).createPullRequestFromPublication(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user));
    }

    @Test
    @DisplayName("6. Crash Recovery: Interrupted PR_CREATING state reconciles matching PR found on GitHub")
    void testCrashRecovery_InterruptedPrCreating_MatchingPrExists_Reconciles() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PR_CREATING);
        publication.setGithubPrNumber(null);
        publication.setPrUrl(null);
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        GithubPullRequestDto prDto = createValidGithubPrDto(77L, branchName, commitSha, baseBranch, githubRepoId, "test-owner", "test-repo");
        when(githubApiService.findPullRequestsByHead(any(), eq("test-owner"), eq("test-repo"), eq(branchName), eq(baseBranch)))
                .thenReturn(List.of(prDto));

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertTrue(result.isCreated());
        assertEquals(77L, result.githubPrNumber());
        assertEquals(PullRequestPublicationStatus.PR_RECONCILED, result.status());
        verify(pullRequestPublicationRepository, times(1)).saveAndFlush(any());
        verify(auditLogService, times(1)).logAction(any(), any(), any(), eq("PUBLICATION_RECONCILED"), any(), any(), any());
    }

    @Test
    @DisplayName("7. Crash Recovery: Interrupted PR_CREATING state with 0 matching PRs executes PR creation boundary")
    void testCrashRecovery_InterruptedPrCreating_NoPrExists_ExecutesPrCreation() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PR_CREATING);
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        when(githubApiService.findPullRequestsByHead(any(), eq("test-owner"), eq("test-repo"), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GitPullRequestResult expectedPrResult = GitPullRequestResult.success(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                88L, "https://github.com/test-owner/test-repo/pull/88", branchName, baseBranch, commitSha, "Pull Request #88", PullRequestPublicationStatus.PR_CREATED
        );
        when(gitPullRequestCreationBoundary.createPullRequestFromPublication(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user)))
                .thenReturn(expectedPrResult);

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertTrue(result.isCreated());
        assertEquals(88L, result.githubPrNumber());
    }

    @Test
    @DisplayName("8. Crash Recovery: Interrupted PUSHING state reconciles existing branch at matching SHA")
    void testCrashRecovery_InterruptedPush_BranchExistsWithMatchingSha_ReconcilesAndExecutesPr() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PUSHING);
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        BranchDto branchDto = new BranchDto();
        branchDto.setName(branchName);
        branchDto.setCommitSha(commitSha);
        when(githubApiService.fetchBranchDetails(any(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenReturn(branchDto);

        GitPullRequestResult expectedPrResult = GitPullRequestResult.success(
                UUID.randomUUID(), approvalId, projectId, repositoryId, taskId, codeChangeId,
                99L, "https://github.com/test-owner/test-repo/pull/99", branchName, baseBranch, commitSha, "Pull Request #99", PullRequestPublicationStatus.PR_CREATED
        );
        when(gitPullRequestCreationBoundary.createPullRequestFromPublication(eq(approvalId), any(), eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(user)))
                .thenReturn(expectedPrResult);

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertTrue(result.isCreated());
        assertEquals(99L, result.githubPrNumber());
        verify(pullRequestPublicationRepository, times(1)).saveAndFlush(any());
    }

    @Test
    @DisplayName("9. Crash Recovery: Interrupted PUSHING state with branch SHA mismatch fails closed as FAILED_CONFLICT")
    void testCrashRecovery_InterruptedPush_BranchShaMismatch_FailsClosed() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PUSHING);
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        BranchDto branchDto = new BranchDto();
        branchDto.setName(branchName);
        branchDto.setCommitSha("different_commit_sha_12345");
        when(githubApiService.fetchBranchDetails(any(), eq("test-owner"), eq("test-repo"), eq(branchName)))
                .thenReturn(branchDto);

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_CONFLICT, result.status());
        assertEquals("REMOTE_BRANCH_CONFLICT", result.failureCategory());
    }

    @Test
    @DisplayName("10. Unknown External State: GitHub API timeout during PR recovery fails closed without re-POSTing")
    void testUnknownExternalState_PrQueryTimeout_FailsClosedWithoutRePosting() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PR_CREATING);
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        when(githubApiService.findPullRequestsByHead(any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.GATEWAY_TIMEOUT, "GitHub API timeout"));

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CREATION, result.status());
        assertEquals("UNKNOWN_EXTERNAL_STATE", result.failureCategory());
        verify(gitPullRequestCreationBoundary, never()).createPullRequestFromPublication(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("11. Fencing Guard: Lock acquisition failure fails closed")
    void testFencing_FencingLockAcquisitionFailure_FailsClosed() {
        when(durableOrchestratorOwnershipService.acquireOwnership(eq(approvalId), any()))
                .thenReturn(null);

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CREATION, result.status());
        assertEquals("FENCING_LOCK_FAILED", result.failureCategory());
    }

    @Test
    @DisplayName("12. Fencing Guard: Stale worker lease loss during save throws IllegalStateException and aborts save")
    void testFencing_StaleWorkerLeaseLossDuringSave_AbortsDbSave() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PR_CREATING);
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        GithubPullRequestDto prDto = createValidGithubPrDto(101L, branchName, commitSha, baseBranch, githubRepoId, "test-owner", "test-repo");
        when(githubApiService.findPullRequestsByHead(any(), any(), any(), any(), any()))
                .thenReturn(List.of(prDto));

        // Lease loss during save operation
        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), eq(fenceToken)))
                .thenReturn(false);

        assertThrows(IllegalStateException.class, () -> {
            boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);
        });

        verify(pullRequestPublicationRepository, never()).saveAndFlush(any());
        verify(durableOrchestratorOwnershipService, never()).releaseOwnership(any(), any());
    }

    @Test
    @DisplayName("13. Terminal State Immutability: FAILED_CONFLICT publication blocks retries")
    void testTerminalState_FailedConflict_BlocksRetry() {
        publication.setPublicationStatus(PullRequestPublicationStatus.FAILED_CONFLICT);
        publication.setFailureCategory("REMOTE_CONFLICT");
        publication.setFailureReason("Conflict on remote branch");
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_CONFLICT, result.status());
        assertEquals("REMOTE_CONFLICT", result.failureCategory());
        verify(auditLogService, times(1)).logAction(any(), any(), any(), eq("PUBLICATION_RETRY_BLOCKED"), any(), any(), any());
    }

    @Test
    @DisplayName("14. Identity Verification: Head repository owner mismatch fails closed as PR_IDENTITY_MISMATCH")
    void testPrIdentityVerification_HeadRepoOwnerMismatch_FailsClosed() {
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));

        GithubPullRequestDto prDto = createValidGithubPrDto(42L, branchName, commitSha, baseBranch, githubRepoId, "malicious-owner", "test-repo");
        when(githubApiService.findPullRequestsByHead(any(), any(), any(), any(), any()))
                .thenReturn(List.of(prDto));

        GitPullRequestResult result = boundary.recoverOrPublish(projectId, repositoryId, taskId, codeChangeId, approvalId, user);

        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("15. Legal & Illegal State Transitions in Recovery Boundary")
    void testValidateStateTransition_LegalAndIllegalTransitions() {
        // Legal
        assertDoesNotThrow(() -> boundary.validateStateTransition(PullRequestPublicationStatus.PENDING, PullRequestPublicationStatus.VERIFYING));
        assertDoesNotThrow(() -> boundary.validateStateTransition(PullRequestPublicationStatus.PUSHING, PullRequestPublicationStatus.RECONCILED));
        assertDoesNotThrow(() -> boundary.validateStateTransition(PullRequestPublicationStatus.PR_CREATING, PullRequestPublicationStatus.PR_RECONCILED));
        assertDoesNotThrow(() -> boundary.validateStateTransition(PullRequestPublicationStatus.FAILED_PUSH, PullRequestPublicationStatus.RECONCILED));
        assertDoesNotThrow(() -> boundary.validateStateTransition(PullRequestPublicationStatus.FAILED_PR_CREATION, PullRequestPublicationStatus.PR_RECONCILED));

        // Illegal
        assertThrows(IllegalStateException.class, () -> boundary.validateStateTransition(PullRequestPublicationStatus.FAILED_CONFLICT, PullRequestPublicationStatus.PR_CREATED));
        assertThrows(IllegalStateException.class, () -> boundary.validateStateTransition(PullRequestPublicationStatus.FAILED_VERIFICATION, PullRequestPublicationStatus.PUSHED));
        assertThrows(IllegalStateException.class, () -> boundary.validateStateTransition(PullRequestPublicationStatus.PR_CREATED, PullRequestPublicationStatus.PENDING));
    }

    private GithubPullRequestDto createValidGithubPrDto(
            Long number, String headBranch, String headSha, String baseBranch, String repoId, String owner, String repoName
    ) {
        GithubPullRequestDto.OwnerRef ownerRef = new GithubPullRequestDto.OwnerRef(owner);
        GithubPullRequestDto.RepoRef repoRef = new GithubPullRequestDto.RepoRef(Long.parseLong(repoId), repoName, ownerRef);
        GithubPullRequestDto.HeadBaseRef headRef = new GithubPullRequestDto.HeadBaseRef(headBranch, headSha, repoRef);
        GithubPullRequestDto.HeadBaseRef baseRef = new GithubPullRequestDto.HeadBaseRef(baseBranch, "parentsha", repoRef);

        return new GithubPullRequestDto(
                1001L, number, "https://github.com/" + owner + "/" + repoName + "/pull/" + number,
                "open", "[RepoPilot] Task", "Body", headRef, baseRef
        );
    }
}
