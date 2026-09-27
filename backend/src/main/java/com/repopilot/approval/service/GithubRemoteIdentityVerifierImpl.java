package com.repopilot.approval.service;

import com.repopilot.approval.dto.GithubRemoteVerificationResult;
import com.repopilot.approval.dto.GithubRemoteVerificationStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
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
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class GithubRemoteIdentityVerifierImpl implements GithubRemoteIdentityVerifier {

    private static final Logger logger = LoggerFactory.getLogger(GithubRemoteIdentityVerifierImpl.class);

    private static final Pattern SAFE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_.-]+$");
    private static final Pattern COMMIT_SHA_PATTERN = Pattern.compile("^[a-fA-F0-9]{40}$");

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final HumanApprovalRepository humanApprovalRepository;
    private final GithubApiService githubApiService;
    private final AuditLogService auditLogService;

    public GithubRemoteIdentityVerifierImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            HumanApprovalRepository humanApprovalRepository,
            GithubApiService githubApiService,
            AuditLogService auditLogService
    ) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.humanApprovalRepository = humanApprovalRepository;
        this.githubApiService = githubApiService;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public GithubRemoteVerificationResult verifyGithubRemoteIdentity(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID approvalId,
            User requester,
            String githubAccessToken
    ) {
        // 1. Authenticated User & Authorization Check
        if (requester == null) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.UNAUTHORIZED,
                    "Authenticated requester required",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        Project project;
        try {
            project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
            projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);
        } catch (ApiException e) {
            logger.warn("Project role authorization check failed for user {}: {}", requester.getId(), e.getMessage());
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.UNAUTHORIZED,
                    "User lacks required project authorization: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 2. Entity Existence & Composite Hierarchy Binding Checks
        HumanApproval approval = humanApprovalRepository.findById(approvalId).orElse(null);
        Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
        Task task = taskRepository.findById(taskId).orElse(null);
        CodeChange codeChange = codeChangeRepository.findById(codeChangeId).orElse(null);

        if (approval == null || repository == null || task == null || codeChange == null) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.APPROVAL_STALE,
                    "One or more required database entities not found",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        if (!approval.getStatus().isApproved() || approval.getApprovalRecordHash() == null) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.APPROVAL_STALE,
                    "HumanApproval record is not APPROVED or approval record hash is missing",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        if (!approval.getProject().getId().equals(projectId) ||
            !approval.getRepository().getId().equals(repositoryId) ||
            !approval.getTask().getId().equals(taskId) ||
            !approval.getCodeChange().getId().equals(codeChangeId) ||
            !repository.getProject().getId().equals(projectId) ||
            !task.getProject().getId().equals(projectId) ||
            !codeChange.getTask().getId().equals(taskId)) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.UNAUTHORIZED,
                    "Entity hierarchy mismatch against approval, project, task, or repository binding",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        String ownerName = repository.getOwnerName();
        String repoName = repository.getRepoName();

        // 3. SSRF & Parameter Injection Defense
        if (!isSafeSegment(ownerName) || !isSafeSegment(repoName)) {
            logger.error("SSRF or invalid character detected in repository parameters: owner='{}', repo='{}'", ownerName, repoName);
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.SSRF_BLOCKED,
                    "Illegal or suspicious characters in repository owner or name parameters",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 4. Remote GitHub Repository Metadata Retrieval
        GithubRepoDto githubRepoDto;
        try {
            githubRepoDto = githubApiService.fetchRepositoryMetadata(githubAccessToken, ownerName, repoName);
        } catch (ApiException e) {
            logger.warn("GitHub API error fetching repository metadata for {}/{}: {}", ownerName, repoName, e.getMessage());
            return mapApiExceptionToResult(e, approvalId, projectId, repositoryId, taskId, codeChangeId);
        } catch (Exception e) {
            logger.error("Unexpected error contacting GitHub API for {}/{}: {}", ownerName, repoName, e.getMessage(), e);
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.GITHUB_API_FAILURE,
                    "Unexpected GitHub API error: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        if (githubRepoDto == null || githubRepoDto.getOwner() == null || githubRepoDto.getName() == null) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.INVALID_GITHUB_RESPONSE,
                    "GitHub API returned malformed or incomplete repository metadata",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 5. Authoritative Identity & Move/Rename Detection
        String remoteId = githubRepoDto.getId();
        String expectedGithubRepoId = repository.getGithubRepoId();

        if (expectedGithubRepoId != null && !expectedGithubRepoId.isBlank()) {
            if (remoteId == null || !remoteId.equalsIgnoreCase(expectedGithubRepoId.trim())) {
                logger.warn("GitHub repository ID mismatch: remoteId={}, expectedId={}", remoteId, expectedGithubRepoId);
                return GithubRemoteVerificationResult.failure(
                        GithubRemoteVerificationStatus.GITHUB_REPOSITORY_MISMATCH,
                        "GitHub repository numeric ID mismatch: remote=" + remoteId + ", expected=" + expectedGithubRepoId,
                        approvalId, projectId, repositoryId, taskId, codeChangeId
                );
            }
        }

        if (!ownerName.equalsIgnoreCase(githubRepoDto.getOwner()) || !repoName.equalsIgnoreCase(githubRepoDto.getName())) {
            logger.warn("GitHub repository owner/name mismatch (renamed/transferred): expected={}/{}, actual={}/{}",
                    ownerName, repoName, githubRepoDto.getOwner(), githubRepoDto.getName());
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.REPOSITORY_MOVED,
                    "GitHub repository owner or name mismatch (repository renamed or transferred): expected=" + ownerName + "/" + repoName + ", remote=" + githubRepoDto.getOwner() + "/" + githubRepoDto.getName(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 6. Authoritative Default Branch Name Resolution
        String defaultBranch = githubRepoDto.getDefaultBranch();
        if (defaultBranch == null || defaultBranch.isBlank()) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.DEFAULT_BRANCH_NOT_FOUND,
                    "GitHub repository default branch metadata is missing",
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }
        defaultBranch = defaultBranch.trim();

        // 7. Authoritative Default Branch SHA Resolution
        BranchDto defaultBranchDto;
        try {
            defaultBranchDto = githubApiService.fetchBranchDetails(githubAccessToken, ownerName, repoName, defaultBranch);
        } catch (ApiException e) {
            logger.warn("GitHub API error fetching default branch details for {}/{} (branch={}): {}", ownerName, repoName, defaultBranch, e.getMessage());
            return mapApiExceptionToResult(e, approvalId, projectId, repositoryId, taskId, codeChangeId);
        } catch (Exception e) {
            logger.error("Unexpected error fetching branch details for {}/{} (branch={}): {}", ownerName, repoName, defaultBranch, e.getMessage(), e);
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.GITHUB_API_FAILURE,
                    "Unexpected error fetching default branch details: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        if (defaultBranchDto == null || defaultBranchDto.getCommitSha() == null || !COMMIT_SHA_PATTERN.matcher(defaultBranchDto.getCommitSha()).matches()) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.INVALID_GITHUB_RESPONSE,
                    "GitHub API returned invalid or malformed commit SHA for default branch " + defaultBranch,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        String githubDefaultBranchSha = defaultBranchDto.getCommitSha().trim().toLowerCase(Locale.ROOT);
        String expectedBaseSha = approval.getRepositoryBaseSha().trim().toLowerCase(Locale.ROOT);

        // 8. Base SHA Parity Comparison
        if (!githubDefaultBranchSha.equalsIgnoreCase(expectedBaseSha)) {
            logger.warn("GitHub default branch SHA mismatch: remoteSha={}, expectedBaseSha={}", githubDefaultBranchSha, expectedBaseSha);
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.BASE_BRANCH_MOVED,
                    "GitHub default branch SHA has moved: remote=" + githubDefaultBranchSha + ", expectedBaseSha=" + expectedBaseSha,
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }

        // 9. Verification Sign-Off & Audit Event
        auditLogService.logAction(
                requester, project, task,
                "GITHUB_IDENTITY_VERIFIED", "HUMAN_APPROVAL", approvalId,
                "{\"githubRepoId\":\"" + remoteId + "\",\"defaultBranch\":\"" + defaultBranch + "\",\"defaultBranchSha\":\"" + githubDefaultBranchSha + "\"}"
        );

        logger.info("Successfully verified GitHub remote identity and default branch SHA for repository {}/{} ({})",
                ownerName, repoName, remoteId);

        return GithubRemoteVerificationResult.verified(
                approvalId, projectId, repositoryId, taskId, codeChangeId,
                repository.getId().toString(), remoteId, ownerName, repoName,
                defaultBranch, githubDefaultBranchSha, expectedBaseSha
        );
    }

    private boolean isSafeSegment(String input) {
        if (input == null || input.isBlank()) return false;
        String lower = input.toLowerCase(Locale.ROOT);
        if (lower.contains("localhost") || lower.contains("127.0.0.1") || lower.contains("0.0.0.0") ||
            lower.contains("169.254.169.254") || lower.contains("..") || lower.contains("/") ||
            lower.contains("\\") || lower.contains(":") || lower.contains("@") || lower.contains("?")) {
            return false;
        }
        return SAFE_NAME_PATTERN.matcher(input).matches();
    }

    private GithubRemoteVerificationResult mapApiExceptionToResult(
            ApiException e, UUID approvalId, UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId
    ) {
        HttpStatus status = e.getStatus();
        if (status == HttpStatus.UNAUTHORIZED) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.GITHUB_AUTH_FAILURE,
                    "GitHub API 401 Unauthorized: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        } else if (status == HttpStatus.FORBIDDEN) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.GITHUB_FORBIDDEN,
                    "GitHub API 403 Forbidden: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.GITHUB_RATE_LIMITED,
                    "GitHub API 429 Rate Limited: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        } else if (status == HttpStatus.NOT_FOUND) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.REPOSITORY_NOT_FOUND,
                    "GitHub API 404 Not Found: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        } else if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.SERVICE_UNAVAILABLE) {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.GITHUB_NETWORK_FAILURE,
                    "GitHub network timeout or gateway failure: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        } else {
            return GithubRemoteVerificationResult.failure(
                    GithubRemoteVerificationStatus.GITHUB_API_FAILURE,
                    "GitHub API error: " + e.getMessage(),
                    approvalId, projectId, repositoryId, taskId, codeChangeId
            );
        }
    }
}
