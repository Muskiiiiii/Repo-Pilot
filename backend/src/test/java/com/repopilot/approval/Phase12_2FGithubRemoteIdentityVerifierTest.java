package com.repopilot.approval;

import com.repopilot.approval.dto.GithubRemoteVerificationResult;
import com.repopilot.approval.dto.GithubRemoteVerificationStatus;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.service.GithubRemoteIdentityVerifier;
import com.repopilot.approval.service.GithubRemoteIdentityVerifierImpl;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.github.dto.BranchDto;
import com.repopilot.github.dto.GithubRepoDto;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase12_2FGithubRemoteIdentityVerifierTest {

    private ProjectAuthorizationService projectAuthorizationService;
    private RepositoryRepository repositoryRepository;
    private TaskRepository taskRepository;
    private CodeChangeRepository codeChangeRepository;
    private HumanApprovalRepository humanApprovalRepository;
    private GithubApiService githubApiService;
    private AuditLogService auditLogService;
    private GithubRemoteIdentityVerifier verifier;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID codeChangeId;
    private UUID approvalId;
    private User requester;

    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private HumanApproval approval;
    private CodeReview review;

    private String baseSha;
    private String token;

    @BeforeEach
    void setUp() {
        projectAuthorizationService = mock(ProjectAuthorizationService.class);
        repositoryRepository = mock(RepositoryRepository.class);
        taskRepository = mock(TaskRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        humanApprovalRepository = mock(HumanApprovalRepository.class);
        githubApiService = mock(GithubApiService.class);
        auditLogService = mock(AuditLogService.class);

        verifier = new GithubRemoteIdentityVerifierImpl(
                projectAuthorizationService,
                repositoryRepository,
                taskRepository,
                codeChangeRepository,
                humanApprovalRepository,
                githubApiService,
                auditLogService
        );

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();
        approvalId = UUID.randomUUID();
        baseSha = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b";
        token = "test_github_access_token_123";

        requester = new User();
        requester.setId(UUID.randomUUID());

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
        codeChange.setRepository(repository);

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

        when(projectAuthorizationService.getAuthorizedProject(projectId, requester)).thenReturn(project);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(codeChangeRepository.findById(codeChangeId)).thenReturn(Optional.of(codeChange));

        GithubRepoDto validRepoDto = new GithubRepoDto(
                "12345678", "test-owner", "test-repo", "test-owner/test-repo",
                "main", "https://github.com/test-owner/test-repo.git",
                "https://github.com/test-owner/test-repo", false, "Test repo"
        );
        when(githubApiService.fetchRepositoryMetadata(eq(token), eq("test-owner"), eq("test-repo"))).thenReturn(validRepoDto);

        BranchDto validBranchDto = new BranchDto(null, "main", baseSha, false);
        when(githubApiService.fetchBranchDetails(eq(token), eq("test-owner"), eq("test-repo"), eq("main"))).thenReturn(validBranchDto);
    }

    @Test
    @DisplayName("1. Valid Identity & Base SHA Parity: Returns VERIFIED")
    void testVerify_Success() {
        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertTrue(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.VERIFIED, result.status());
        assertEquals("12345678", result.githubRepoId());
        assertEquals("main", result.defaultBranch());
        assertEquals(baseSha, result.defaultBranchSha());
    }

    @Test
    @DisplayName("2. GitHub Repository Numeric ID Mismatch: Returns GITHUB_REPOSITORY_MISMATCH")
    void testVerify_RepositoryIdMismatch() {
        GithubRepoDto badIdRepoDto = new GithubRepoDto(
                "87654321", "test-owner", "test-repo", "test-owner/test-repo",
                "main", "https://github.com/test-owner/test-repo.git",
                "https://github.com/test-owner/test-repo", false, "Test repo"
        );
        when(githubApiService.fetchRepositoryMetadata(eq(token), eq("test-owner"), eq("test-repo"))).thenReturn(badIdRepoDto);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.GITHUB_REPOSITORY_MISMATCH, result.status());
    }

    @Test
    @DisplayName("3. Owner Name Mismatch (Transferred): Returns REPOSITORY_MOVED")
    void testVerify_OwnerMismatch() {
        GithubRepoDto movedOwnerDto = new GithubRepoDto(
                "12345678", "new-owner", "test-repo", "new-owner/test-repo",
                "main", "https://github.com/new-owner/test-repo.git",
                "https://github.com/new-owner/test-repo", false, "Test repo"
        );
        when(githubApiService.fetchRepositoryMetadata(eq(token), eq("test-owner"), eq("test-repo"))).thenReturn(movedOwnerDto);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.REPOSITORY_MOVED, result.status());
    }

    @Test
    @DisplayName("4. Repository Name Mismatch (Renamed): Returns REPOSITORY_MOVED")
    void testVerify_RepoNameMismatch() {
        GithubRepoDto renamedDto = new GithubRepoDto(
                "12345678", "test-owner", "renamed-repo", "test-owner/renamed-repo",
                "main", "https://github.com/test-owner/renamed-repo.git",
                "https://github.com/test-owner/renamed-repo", false, "Test repo"
        );
        when(githubApiService.fetchRepositoryMetadata(eq(token), eq("test-owner"), eq("test-repo"))).thenReturn(renamedDto);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.REPOSITORY_MOVED, result.status());
    }

    @Test
    @DisplayName("5. Base SHA Moved on GitHub: Returns BASE_BRANCH_MOVED")
    void testVerify_BaseBranchMoved() {
        String newSha = "ffffffffffffffffffffffffffffffffffffffff";
        BranchDto movedBranchDto = new BranchDto(null, "main", newSha, false);
        when(githubApiService.fetchBranchDetails(eq(token), eq("test-owner"), eq("test-repo"), eq("main"))).thenReturn(movedBranchDto);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.BASE_BRANCH_MOVED, result.status());
    }

    @Test
    @DisplayName("6. Custom Default Branch ('master' or 'develop'): Resolves custom default branch")
    void testVerify_CustomDefaultBranch() {
        repository.setDefaultBranch("develop");
        GithubRepoDto developRepoDto = new GithubRepoDto(
                "12345678", "test-owner", "test-repo", "test-owner/test-repo",
                "develop", "https://github.com/test-owner/test-repo.git",
                "https://github.com/test-owner/test-repo", false, "Test repo"
        );
        when(githubApiService.fetchRepositoryMetadata(eq(token), eq("test-owner"), eq("test-repo"))).thenReturn(developRepoDto);

        BranchDto developBranchDto = new BranchDto(null, "develop", baseSha, false);
        when(githubApiService.fetchBranchDetails(eq(token), eq("test-owner"), eq("test-repo"), eq("develop"))).thenReturn(developBranchDto);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertTrue(result.isVerified());
        assertEquals("develop", result.defaultBranch());
    }

    @Test
    @DisplayName("7. Missing Default Branch Metadata: Returns DEFAULT_BRANCH_NOT_FOUND")
    void testVerify_DefaultBranchMissing() {
        GithubRepoDto noDefaultRepoDto = new GithubRepoDto(
                "12345678", "test-owner", "test-repo", "test-owner/test-repo",
                "", "https://github.com/test-owner/test-repo.git",
                "https://github.com/test-owner/test-repo", false, "Test repo"
        );
        when(githubApiService.fetchRepositoryMetadata(eq(token), eq("test-owner"), eq("test-repo"))).thenReturn(noDefaultRepoDto);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.DEFAULT_BRANCH_NOT_FOUND, result.status());
    }

    @Test
    @DisplayName("8. SSRF Injection Attack in Owner/Repo Name: Returns SSRF_BLOCKED")
    void testVerify_SSRFBlocked() {
        repository.setOwnerName("localhost");
        repository.setRepoName("169.254.169.254");

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.SSRF_BLOCKED, result.status());
    }

    @Test
    @DisplayName("9. Path Traversal Injection in Repo Name: Returns SSRF_BLOCKED")
    void testVerify_PathTraversalBlocked() {
        repository.setRepoName("../../../etc/passwd");

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.SSRF_BLOCKED, result.status());
    }

    @Test
    @DisplayName("10. GitHub API 401 Unauthorized: Returns GITHUB_AUTH_FAILURE")
    void testVerify_GitHub401Unauthorized() {
        when(githubApiService.fetchRepositoryMetadata(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "GITHUB_UNAUTHORIZED", "Token invalid"));

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.GITHUB_AUTH_FAILURE, result.status());
    }

    @Test
    @DisplayName("11. GitHub API 403 Forbidden: Returns GITHUB_FORBIDDEN")
    void testVerify_GitHub403Forbidden() {
        when(githubApiService.fetchRepositoryMetadata(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "GITHUB_FORBIDDEN", "Forbidden"));

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.GITHUB_FORBIDDEN, result.status());
    }

    @Test
    @DisplayName("12. GitHub API 404 Not Found: Returns REPOSITORY_NOT_FOUND")
    void testVerify_GitHub404NotFound() {
        when(githubApiService.fetchRepositoryMetadata(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "GITHUB_NOT_FOUND", "Repo not found"));

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.REPOSITORY_NOT_FOUND, result.status());
    }

    @Test
    @DisplayName("13. GitHub API 429 Rate Limited: Returns GITHUB_RATE_LIMITED")
    void testVerify_GitHub429RateLimited() {
        when(githubApiService.fetchRepositoryMetadata(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "GITHUB_RATE_LIMIT_EXHAUSTED", "Rate limit exhausted"));

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.GITHUB_RATE_LIMITED, result.status());
    }

    @Test
    @DisplayName("14. GitHub API Network Timeout / 502: Returns GITHUB_NETWORK_FAILURE")
    void testVerify_GitHub502NetworkFailure() {
        when(githubApiService.fetchRepositoryMetadata(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Timeout connecting to GitHub"));

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.GITHUB_NETWORK_FAILURE, result.status());
    }

    @Test
    @DisplayName("15. Malformed Commit SHA in Default Branch Response: Returns INVALID_GITHUB_RESPONSE")
    void testVerify_MalformedBranchCommitSha() {
        BranchDto malformedBranch = new BranchDto(null, "main", "short_sha", false);
        when(githubApiService.fetchBranchDetails(eq(token), eq("test-owner"), eq("test-repo"), eq("main"))).thenReturn(malformedBranch);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.INVALID_GITHUB_RESPONSE, result.status());
    }

    @Test
    @DisplayName("16. Unauthorized Project Member Role (Role VIEWER Rejected): Returns UNAUTHORIZED")
    void testVerify_RoleAuthorizationFailed() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "Requires minimum role MEMBER"))
                .when(projectAuthorizationService).requireMinimumRole(eq(project), eq(requester), eq(ProjectRole.MEMBER));

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.UNAUTHORIZED, result.status());
    }

    @Test
    @DisplayName("17. Stale HumanApproval (Status REJECTED): Returns APPROVAL_STALE")
    void testVerify_StaleApprovalStatus() {
        approval.setStatus(ApprovalStatus.REJECTED);

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.APPROVAL_STALE, result.status());
    }

    @Test
    @DisplayName("18. Cross-Project Approval Binding Mismatch: Returns UNAUTHORIZED")
    void testVerify_CrossProjectBindingMismatch() {
        Project wrongProject = new Project();
        wrongProject.setId(UUID.randomUUID());

        HumanApproval wrongProjectApproval = new HumanApproval();
        wrongProjectApproval.setId(approvalId);
        wrongProjectApproval.setProject(wrongProject);
        wrongProjectApproval.setRepository(repository);
        wrongProjectApproval.setTask(task);
        wrongProjectApproval.setCodeChange(codeChange);
        wrongProjectApproval.setReview(review);
        wrongProjectApproval.setRepositoryBaseSha(baseSha);
        wrongProjectApproval.setApprovalRecordHash("valid_record_hash");
        wrongProjectApproval.setStatus(ApprovalStatus.APPROVED);

        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(wrongProjectApproval));

        GithubRemoteVerificationResult result = verifier.verifyGithubRemoteIdentity(
                projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(GithubRemoteVerificationStatus.UNAUTHORIZED, result.status());
    }
}
