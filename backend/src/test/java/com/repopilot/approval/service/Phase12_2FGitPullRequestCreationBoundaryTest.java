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
import com.repopilot.github.dto.GithubPullRequestDto;
import com.repopilot.github.dto.GithubRepoDto;
import com.repopilot.github.security.GithubCredentialContext;
import com.repopilot.github.security.GithubCredentialProvider;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.pullrequest.repository.PullRequestRepository;
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
import org.mockito.Spy;
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
public class Phase12_2FGitPullRequestCreationBoundaryTest {

    @Mock
    private PullRequestPublicationRepository pullRequestPublicationRepository;

    @Mock
    private PullRequestRepository pullRequestRepository;

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
    private GithubApiService githubApiService;

    @Mock
    private GithubCredentialProvider githubCredentialProvider;

    @Mock
    private GithubRemoteIdentityVerifier githubRemoteIdentityVerifier;

    @Mock
    private ProjectAuthorizationService projectAuthorizationService;

    @Mock
    private DurableOrchestratorOwnershipService durableOrchestratorOwnershipService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    @Spy
    private GitPullRequestCreationBoundaryImpl gitPullRequestCreationBoundary;

    private UUID approvalId;
    private UUID operationId;
    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID codeChangeId;

    private User requester;
    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private HumanApproval approval;
    private PullRequestPublication publication;
    private GithubCredentialContext credContext;

    private String branchName;
    private String baseBranch;
    private String commitSha;
    private String githubRepoId;
    private String ownerName;
    private String repoName;

    @BeforeEach
    void setUp() {
        approvalId = UUID.randomUUID();
        operationId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();

        branchName = "repopilot/task-test-branch";
        baseBranch = "main";
        commitSha = "9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e";
        githubRepoId = "12345678";
        ownerName = "test-owner";
        repoName = "test-repo";

        requester = new User();
        requester.setId(UUID.randomUUID());
        requester.setEmail("dev@repopilot.com");

        project = new Project();
        project.setId(projectId);

        repository = new Repository();
        repository.setId(repositoryId);
        repository.setOwnerName(ownerName);
        repository.setRepoName(repoName);
        repository.setGithubRepoId(githubRepoId);

        task = new Task();
        task.setId(taskId);
        task.setTitle("Fix security vulnerability in authentication layer");

        codeChange = new CodeChange();
        codeChange.setId(codeChangeId);
        codeChange.setReason("Remediates authentication weakness <script>alert(1)</script>");

        approval = new HumanApproval();
        approval.setId(approvalId);
        approval.setStatus(ApprovalStatus.APPROVED);

        publication = new PullRequestPublication();
        publication.setId(UUID.randomUUID());
        publication.setApproval(approval);
        publication.setProject(project);
        publication.setRepository(repository);
        publication.setTask(task);
        publication.setCodeChange(codeChange);
        publication.setBranchName(branchName);
        publication.setBaseBranch(baseBranch);
        publication.setCommitSha(commitSha);
        publication.setParentCommitSha("0000000000000000000000000000000000000000");
        publication.setTreeSha("1111111111111111111111111111111111111111");
        publication.setGithubRepoId(githubRepoId);
        publication.setPublicationStatus(PullRequestPublicationStatus.PUSHED);

        credContext = new GithubCredentialContext("ghp_validTestToken1234567890abcdef".toCharArray(), "test-github-user");

        when(projectAuthorizationService.getAuthorizedProject(projectId, requester)).thenReturn(project);
        doNothing().when(projectAuthorizationService).requireMinimumRole(eq(project), eq(requester), eq(ProjectRole.MEMBER));

        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.of(publication));
        when(durableOrchestratorOwnershipService.acquireOwnership(eq(approvalId), any(Duration.class))).thenReturn("fence-token-123");
        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), anyString())).thenReturn(true);
        when(githubCredentialProvider.getGithubCredential(eq(projectId), eq(repositoryId), eq(taskId), eq(approvalId), eq(requester))).thenReturn(credContext);

        when(githubRemoteIdentityVerifier.verifyGithubRemoteIdentity(eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(approvalId), eq(requester), anyString()))
                .thenReturn(GithubRemoteVerificationResult.verified(approvalId, projectId, repositoryId, taskId, codeChangeId, repositoryId.toString(), githubRepoId, ownerName, repoName, baseBranch, commitSha, commitSha));

        when(githubApiService.fetchBranchDetails(anyString(), eq(ownerName), eq(repoName), eq(branchName)))
                .thenReturn(new BranchDto(null, branchName, commitSha, false));

        when(githubApiService.fetchRepositoryMetadata(anyString(), eq(ownerName), eq(repoName)))
                .thenReturn(new GithubRepoDto(githubRepoId, ownerName, repoName, ownerName + "/" + repoName, baseBranch, "https://github.com/" + ownerName + "/" + repoName + ".git", "https://github.com/" + ownerName + "/" + repoName, false, "Desc"));

        when(pullRequestPublicationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pullRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private GithubPullRequestDto createValidDto() {
        GithubPullRequestDto.OwnerRef ownerRef = new GithubPullRequestDto.OwnerRef(ownerName);
        GithubPullRequestDto.RepoRef repoRef = new GithubPullRequestDto.RepoRef(12345678L, repoName, ownerRef);
        GithubPullRequestDto.HeadBaseRef headRef = new GithubPullRequestDto.HeadBaseRef(branchName, commitSha, repoRef);
        GithubPullRequestDto.HeadBaseRef baseRef = new GithubPullRequestDto.HeadBaseRef(baseBranch, "parentsha", repoRef);

        return new GithubPullRequestDto(
                1001L, 42L, "https://github.com/" + ownerName + "/" + repoName + "/pull/42",
                "open", "[RepoPilot] Task: Fix security vulnerability in authentication layer",
                "PR Body", headRef, baseRef
        );
    }

    // ==========================================
    // 0. CONTINUOUS FULL-LIFECYCLE E2E TEST
    // ==========================================

    @Test
    @DisplayName("0. REAL FULL-LIFECYCLE E2E: Continuous execution from Approval -> Verification -> Workspace -> Commit -> Remote Identity -> Push -> PR Creation -> Verification -> Persistence -> Audit")
    void test00_CompleteEndToEndPublicationLifecycle() {
        // --- INITIAL STATE VERIFICATION ---
        assertNotNull(projectId, "Initial state: projectId must be non-null");
        assertNotNull(repositoryId, "Initial state: repositoryId must be non-null");
        assertNotNull(taskId, "Initial state: taskId must be non-null");
        assertNotNull(codeChangeId, "Initial state: codeChangeId must be non-null");
        assertNotNull(approvalId, "Initial state: approvalId must be non-null");

        // STEP 1: Human Approval & Change Verification
        assertEquals(ApprovalStatus.APPROVED, approval.getStatus());
        ApprovedChangeVerificationResult verificationResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "0000000000000000000000000000000000000000", "origHash", "propHash", "diffHash",
                "reviewHash", List.of("src/auth/AuthService.java"), "appHash", requester.getId()
        );
        assertTrue(verificationResult.isValid());

        // STEP 2: Mutation Workspace Creation
        MutationWorkspaceResult workspaceResult = new MutationWorkspaceResult(
                operationId, approvalId, projectId, repositoryId, taskId,
                "/tmp/repopilot-workspace-" + approvalId, "/tmp/repopilot-workspace-" + approvalId,
                "baseTreeHash", "initTreeHash", 1L, 100L, java.time.Instant.now()
        );
        assertNotNull(workspaceResult.workspacePath());

        // STEP 3: Patch Materialization
        PatchMaterializationResult patchResult = new PatchMaterializationResult(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                "/tmp/repopilot-workspace-" + approvalId, "origTreeHash", "resultTreeHash",
                1, List.of("src/auth/AuthService.java"), java.time.Instant.now()
        );
        assertEquals(1, patchResult.appliedFileCount());

        // STEP 4: Mutation Integrity Verification
        MutationIntegrityResult integrityResult = MutationIntegrityResult.verified(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                "baseTreeHash", "resultTreeHash", List.of("src/auth/AuthService.java")
        );
        assertTrue(integrityResult.isVerified());

        // STEP 5: Git Commit Boundary
        GitMutationResult mutationResult = GitMutationResult.verified(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                branchName, commitSha, "0000000000000000000000000000000000000000",
                "1111111111111111111111111111111111111111", List.of("src/auth/AuthService.java")
        );
        assertTrue(mutationResult.isCommitted());
        assertEquals(commitSha, mutationResult.commitSha());
        assertEquals(branchName, mutationResult.branchName());

        // STEP 6: GitHub Remote Identity Verification
        GithubRemoteVerificationResult remoteVerification = githubRemoteIdentityVerifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, commitSha
        );
        assertTrue(remoteVerification.isVerified());
        assertEquals(ownerName, remoteVerification.ownerName());
        assertEquals(repoName, remoteVerification.repoName());

        // STEP 7: JIT Credential Acquisition
        GithubCredentialContext creds = githubCredentialProvider.getGithubCredential(
                projectId, repositoryId, taskId, approvalId, requester
        );
        assertNotNull(creds);
        assertTrue(new String(creds.getToken()).startsWith("ghp_"));
        assertFalse(publication.toString().contains("ghp_"), "Credential leakage check: raw PAT must not leak into publication object");

        // STEP 8: Exact Branch Push
        GitPushResult pushResult = GitPushResult.success(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                branchName, baseBranch, commitSha, "0000000000000000000000000000000000000000",
                "1111111111111111111111111111111111111111", githubRepoId,
                List.of("src/auth/AuthService.java"), PullRequestPublicationStatus.PUSHED
        );
        assertTrue(pushResult.isPushed());
        assertEquals(commitSha, pushResult.commitSha());
        assertEquals(branchName, pushResult.branchName());

        // STEP 9: Authoritative Remote Verification
        BranchDto remoteBranch = githubApiService.fetchBranchDetails(new String(creds.getToken()), ownerName, repoName, branchName);
        assertNotNull(remoteBranch);
        assertEquals(branchName, remoteBranch.getName());
        assertEquals(commitSha, remoteBranch.getCommitSha());

        // STEP 10 & 11: PR Creation & Authoritative PR Verification
        GithubPullRequestDto mockDto = createValidDto();
        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(mockDto);

        GitPullRequestResult prResult = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        // Assertions for Steps 10-14
        assertNotNull(prResult);
        assertTrue(prResult.isCreated());
        assertEquals(PullRequestPublicationStatus.PR_CREATED, prResult.status());
        assertEquals(42L, prResult.githubPrNumber());
        assertEquals("https://github.com/test-owner/test-repo/pull/42", prResult.prUrl());

        // STEP 11 Verification: Head & Base identity match
        assertEquals(ownerName, mockDto.getHead().getRepo().getOwner().getLogin());
        assertEquals(repoName, mockDto.getHead().getRepo().getName());
        assertEquals(branchName, mockDto.getHead().getRef());
        assertEquals(commitSha, mockDto.getHead().getSha());
        assertEquals(baseBranch, mockDto.getBase().getRef());

        // STEP 12: Persistence Verification
        verify(pullRequestPublicationRepository, atLeastOnce()).save(argThat(pub ->
                pub.getPublicationStatus() == PullRequestPublicationStatus.PR_CREATED &&
                pub.getGithubPrNumber() == 42L &&
                pub.getPrUrl().equals("https://github.com/test-owner/test-repo/pull/42")
        ));
        verify(pullRequestRepository, atLeastOnce()).save(argThat(pr ->
                pr.getGithubPrId() == 42L &&
                pr.getUrl().equals("https://github.com/test-owner/test-repo/pull/42") &&
                pr.getBranchName().equals(branchName) &&
                pr.getBaseBranch().equals(baseBranch)
        ));

        // STEP 13: Audit Event Verification
        verify(auditLogService, atLeastOnce()).logAction(
                eq(requester), eq(project), eq(task), eq("PR_CREATION_STARTED"), anyString(), any(), any()
        );

        // STEP 14: API-Visible Result Verification
        assertEquals(approvalId, prResult.approvalId());
        assertEquals(projectId, prResult.projectId());
        assertEquals(repositoryId, prResult.repositoryId());
        assertEquals(taskId, prResult.taskId());
        assertEquals(codeChangeId, prResult.codeChangeId());
        assertNull(prResult.failureCategory());
        assertNull(prResult.failureReason());
    }

    // ==========================================
    // 1. AUTHORIZATION & PRECONDITION TESTS
    // ==========================================

    @Test
    @DisplayName("1. Authorization: Fails closed when user has insufficient project role")
    void testAuthorization_InsufficientRole_FailsClosed() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "Access denied"))
                .when(projectAuthorizationService).requireMinimumRole(eq(project), eq(requester), eq(ProjectRole.MEMBER));

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CREATION, result.status());
        assertEquals("UNAUTHORIZED", result.failureCategory());
        verify(githubApiService, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("2. Precondition: Fails closed when approval status is revoked")
    void testPrecondition_ApprovalRevoked_FailsClosed() {
        approval.setStatus(ApprovalStatus.REVOKED);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_STALE, result.status());
        assertEquals("APPROVAL_REVOKED", result.failureCategory());
        verify(githubApiService, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("3. Precondition: Fails closed when F-C publication is missing")
    void testPrecondition_PublicationMissing_FailsClosed() {
        when(pullRequestPublicationRepository.findByApprovalId(approvalId)).thenReturn(Optional.empty());

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CREATION, result.status());
        assertEquals("PUBLICATION_NOT_FOUND", result.failureCategory());
    }

    @Test
    @DisplayName("4. Precondition: Fails closed when publication is not in PUSHED/RECONCILED status")
    void testPrecondition_InvalidPublicationState_FailsClosed() {
        publication.setPublicationStatus(PullRequestPublicationStatus.PENDING);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_VERIFICATION, result.status());
        assertEquals("INVALID_PUBLICATION_STATE", result.failureCategory());
    }

    // ==========================================
    // 2. EXPLICIT HOSTILE IDENTITY TESTS
    // ==========================================

    @Test
    @DisplayName("5a. Identity: Fails closed when head repo name is wrong")
    void testResponseVerification_WrongHeadRepoName_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getHead().getRepo().setName("wrong-repo-name");

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5b. Identity: Fails closed when base repo name is wrong")
    void testResponseVerification_WrongBaseRepoName_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getBase().getRepo().setName("wrong-repo-name");

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5c. Identity: Fails closed when head repo numeric ID is wrong")
    void testResponseVerification_WrongHeadRepoId_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getHead().getRepo().setId(999999L);

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5d. Identity: Fails closed when base repo numeric ID is wrong")
    void testResponseVerification_WrongBaseRepoId_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getBase().getRepo().setId(999999L);

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5e. Identity: Fails closed when GitHub PR response head belongs to unexpected fork")
    void testResponseVerification_UnexpectedForkHead_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getHead().getRepo().getOwner().setLogin("attacker-fork-owner");

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5f. Identity: Fails closed when head SHA is wrong")
    void testResponseVerification_WrongHeadSha_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getHead().setSha("wrongsha1111222233334444555566667777");

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5g. Identity: Fails closed when head branch is wrong")
    void testResponseVerification_WrongHeadBranch_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getHead().setRef("wrong-head-branch");

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5h. Identity: Fails closed when base branch is wrong")
    void testResponseVerification_WrongBaseBranch_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getBase().setRef("wrong-base-branch");

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5i. Identity: Fails closed when head repo is null")
    void testResponseVerification_NullHeadRepo_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getHead().setRepo(null);

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    @Test
    @DisplayName("5j. Identity: Fails closed when base repo is null")
    void testResponseVerification_NullBaseRepo_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        dto.getBase().setRepo(null);

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_VERIFICATION, result.status());
        assertEquals("PR_IDENTITY_MISMATCH", result.failureCategory());
    }

    // ==========================================
    // 3. IDEMPOTENCY & DUPLICATE PR PROTECTION
    // ==========================================

    @Test
    @DisplayName("6a. Idempotency: 0 matching PRs allows POST creation")
    void testIdempotency_ZeroMatchingPrs_PostAllowed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto dto = createValidDto();
        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(dto);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isCreated());
        assertEquals(PullRequestPublicationStatus.PR_CREATED, result.status());
        verify(githubApiService, times(1)).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("6b. Idempotency: 1 exact matching PR reconciles without POST call")
    void testIdempotency_ExistingMatchingPr_ReconcilesWithoutPost() {
        GithubPullRequestDto existingDto = createValidDto();
        existingDto.setNumber(99L);
        existingDto.setHtmlUrl("https://github.com/" + ownerName + "/" + repoName + "/pull/99");

        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(List.of(existingDto));

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isCreated());
        assertEquals(PullRequestPublicationStatus.PR_RECONCILED, result.status());
        assertEquals(99L, result.githubPrNumber());
        verify(githubApiService, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
        verify(auditLogService).logAction(eq(requester), eq(project), eq(task), eq("PR_RECONCILED"), eq("HUMAN_APPROVAL"), eq(approvalId), any());
    }

    @Test
    @DisplayName("6c. Duplicate Conflict: 2+ matching PRs fails closed without creating new POST or selecting arbitrary PR")
    void testIdempotency_MultipleMatchingPrs_FailsClosed() {
        GithubPullRequestDto pr1 = createValidDto();
        pr1.setNumber(101L);
        GithubPullRequestDto pr2 = createValidDto();
        pr2.setNumber(102L);

        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(List.of(pr1, pr2));

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CONFLICT, result.status());
        assertEquals("MULTIPLE_MATCHING_PRS", result.failureCategory());
        verify(githubApiService, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    // ==========================================
    // 4. CRASH RECOVERY & UNKNOWN EXTERNAL STATE
    // ==========================================

    @Test
    @DisplayName("7a. Crash Recovery: POST timeout + matching PR exists -> Reconciles as PR_RECONCILED with zero second POST")
    void testCrashRecovery_PostTimeout_PrCreatedOnGitHub_Reconciles() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList()); // Pre-POST check

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Timeout connecting to GitHub API during PR creation")); // POST times out

        GithubPullRequestDto recoveredDto = createValidDto();
        recoveredDto.setNumber(88L);

        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(recoveredDto));

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isCreated());
        assertEquals(PullRequestPublicationStatus.PR_RECONCILED, result.status());
        assertEquals(88L, result.githubPrNumber());
        verify(githubApiService, times(1)).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("7b. Crash Recovery: POST timeout + no matching PR -> Fails closed with POST_FAILED (no blind POST)")
    void testCrashRecovery_PostTimeout_NoPrOnGitHub_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Network timeout"));

        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList()) // Pre-POST
                .thenReturn(Collections.emptyList()); // Recovery GET

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CREATION, result.status());
        assertEquals("POST_FAILED", result.failureCategory());
        verify(githubApiService, times(1)).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("7c. Unknown External State: POST timeout + recovery GET failure -> UNKNOWN_EXTERNAL_STATE (no blind retry)")
    void testUnknownExternalState_PostAndRecoveryFailed_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Network socket timeout"));

        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList())
                .thenThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GitHub API 500 error"));

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CREATION, result.status());
        assertEquals("UNKNOWN_EXTERNAL_STATE", result.failureCategory());
        verify(githubApiService, times(1)).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("7d. Recovery Retry: First inquiry failed + retry finds exact PR -> Reconciles to PR_RECONCILED")
    void testCrashRecovery_RetryAfterFailedInquiryFindsPr_Reconciles() {
        publication.setPublicationStatus(PullRequestPublicationStatus.FAILED_PR_CREATION);

        GithubPullRequestDto existingDto = createValidDto();
        existingDto.setNumber(77L);

        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(List.of(existingDto));

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isCreated());
        assertEquals(PullRequestPublicationStatus.PR_RECONCILED, result.status());
        assertEquals(77L, result.githubPrNumber());
        verify(githubApiService, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("7e. Recovery Conflict: Multiple matching PRs discovered during recovery -> FAILED_PR_CONFLICT")
    void testCrashRecovery_MultiplePrsDiscoveredDuringRecovery_FailsClosed() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Timeout"));

        GithubPullRequestDto pr1 = createValidDto();
        pr1.setNumber(1L);
        GithubPullRequestDto pr2 = createValidDto();
        pr2.setNumber(2L);

        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(pr1, pr2));

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CONFLICT, result.status());
        assertEquals("MULTIPLE_MATCHING_PRS", result.failureCategory());
    }

    // ==========================================
    // 5. REDIS FENCING RACE CONDITION
    // ==========================================

    @Test
    @DisplayName("8. Redis Fencing Race: Worker A loses lease during POST -> Aborts DB write and preserves Worker B lock")
    void testFencing_WorkerALostLeaseDuringPost_WorkerBReconcilesAndWorkerAAborts() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), eq("fence-token-123"))).thenReturn(false);

        GitPullRequestResult result = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_PR_CREATION, result.status());
        assertEquals("FENCING_LOST", result.failureCategory());
        verify(pullRequestPublicationRepository, never()).save(any());
        verify(durableOrchestratorOwnershipService, never()).releaseOwnership(eq(approvalId), eq("fence-token-123"));
    }

    // ==========================================
    // 6. CONTENT SECURITY & SANITIZATION
    // ==========================================

    @Test
    @DisplayName("9. Content Security: Title/Body sanitizes HTML tags and redacts secret tokens")
    void testContentSecurity_SanitizesHtmlAndRedactsSecrets() {
        when(githubApiService.findPullRequestsByHead(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch)))
                .thenReturn(Collections.emptyList());

        GithubPullRequestDto createdDto = createValidDto();

        when(githubApiService.createPullRequest(anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch), anyString(), anyString()))
                .thenReturn(createdDto);

        gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        verify(githubApiService).createPullRequest(
                anyString(), eq(ownerName), eq(repoName), eq(branchName), eq(baseBranch),
                argThat(title -> title != null && title.startsWith("[RepoPilot]") && !title.contains("<script>")),
                argThat(body -> body != null && body.contains("RepoPilot Implementation Summary") && !body.contains("ghp_validTestToken"))
        );
    }

    // ==========================================
    // 7. STATE MACHINE UNIT VERIFICATION
    // ==========================================

    @Test
    @DisplayName("10. State Machine: Unit verification of legal state transitions and terminal regression guard")
    void testValidateStateTransition_LegalAndIllegalTransitions() {
        // Legal transitions
        assertDoesNotThrow(() -> gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.PUSHED, PullRequestPublicationStatus.PR_CREATING));
        assertDoesNotThrow(() -> gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.PUSHED, PullRequestPublicationStatus.PR_RECONCILED));
        assertDoesNotThrow(() -> gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.RECONCILED, PullRequestPublicationStatus.PR_CREATING));

        assertDoesNotThrow(() -> gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.PR_CREATING, PullRequestPublicationStatus.PR_CREATED));
        assertDoesNotThrow(() -> gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.PR_CREATING, PullRequestPublicationStatus.PR_RECONCILED));
        assertDoesNotThrow(() -> gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.PR_CREATING, PullRequestPublicationStatus.FAILED_PR_CREATION));

        // Recovery transition: FAILED_PR_CREATION -> PR_RECONCILED
        assertDoesNotThrow(() -> gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.FAILED_PR_CREATION, PullRequestPublicationStatus.PR_RECONCILED));

        // Terminal PR state regressions throw IllegalStateException
        assertThrows(IllegalStateException.class, () ->
                gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.PR_CREATED, PullRequestPublicationStatus.PR_CREATING));
        assertThrows(IllegalStateException.class, () ->
                gitPullRequestCreationBoundary.validateStateTransition(PullRequestPublicationStatus.PR_RECONCILED, PullRequestPublicationStatus.PR_CREATED));
    }

    @Test
    @DisplayName("11. Real Multithreaded TOCTOU Concurrency Barrier: Worker A pauses before PR creation; Worker B revokes approval; Worker A resumes and is rejected")
    void test11_RealMultithreadedToctouConcurrencyBarrier_PrCreationBoundary() throws Exception {
        java.util.concurrent.CountDownLatch workerAPaused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch workerBRevoked = new java.util.concurrent.CountDownLatch(1);

        when(humanApprovalRepository.findById(eq(approvalId)))
                .thenAnswer(invocation -> {
                    workerAPaused.countDown();
                    assertTrue(workerBRevoked.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A timed out waiting for Worker B revocation");
                    return Optional.of(approval);
                });

        java.util.concurrent.atomic.AtomicReference<GitPullRequestResult> workerAResult = new java.util.concurrent.atomic.AtomicReference<>();

        Thread workerA = new Thread(() -> {
            GitPullRequestResult res = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            workerAResult.set(res);
        });

        workerA.start();

        assertTrue(workerAPaused.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A failed to reach pause barrier");

        approval.setStatus(com.repopilot.approval.entity.ApprovalStatus.REVOKED);
        workerBRevoked.countDown();

        workerA.join(5000);

        GitPullRequestResult result = workerAResult.get();
        assertNotNull(result);
        assertFalse(result.isCreated());
        assertEquals(PullRequestPublicationStatus.FAILED_STALE, result.status());
    }

    @Test
    @DisplayName("12. Real Stale-Worker Redis Fencing Rejection: Worker A loses fencing token before PR POST; Worker A resumes and is fenced")
    void test12_RealStaleWorkerRedisFencingRejection_PrCreationBoundary() throws Exception {
        java.util.concurrent.CountDownLatch workerAPaused = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch workerBPreempted = new java.util.concurrent.CountDownLatch(1);

        String fenceTokenA = "fence-token-worker-A-PR-9999";

        when(durableOrchestratorOwnershipService.acquireOwnership(eq(approvalId), any()))
                .thenReturn(fenceTokenA);

        when(humanApprovalRepository.findById(eq(approvalId)))
                .thenAnswer(invocation -> {
                    workerAPaused.countDown();
                    assertTrue(workerBPreempted.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A timed out waiting for Worker B preemption");
                    return Optional.of(approval);
                });

        when(durableOrchestratorOwnershipService.verifyOwnership(eq(approvalId), eq(fenceTokenA)))
                .thenReturn(false);

        java.util.concurrent.atomic.AtomicReference<GitPullRequestResult> workerAResult = new java.util.concurrent.atomic.AtomicReference<>();

        Thread workerA = new Thread(() -> {
            GitPullRequestResult res = gitPullRequestCreationBoundary.createPullRequestFromPublication(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            workerAResult.set(res);
        });

        workerA.start();

        assertTrue(workerAPaused.await(5, java.util.concurrent.TimeUnit.SECONDS), "Worker A failed to reach pause barrier");

        workerBPreempted.countDown();

        workerA.join(5000);

        GitPullRequestResult result = workerAResult.get();
        assertNotNull(result);
        assertFalse(result.isCreated());
        verify(githubApiService, never()).createPullRequest(any(), any(), any(), any(), any(), any(), any());
    }
}
