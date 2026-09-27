package com.repopilot.approval.service;

import com.repopilot.approval.dto.GitPullRequestResult;
import com.repopilot.approval.dto.GitPushResult;
import com.repopilot.approval.dto.GithubRemoteVerificationResult;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of Phase 12.2-F-E — Publication Idempotency & Recovery Boundary.
 *
 * Orchestrates and recovers the complete external publication workflow (F-C branch publication and F-D PR creation)
 * in an idempotent, crash-resilient, and failure-aware manner across worker crashes, network timeouts, Redis lease expirations,
 * and stale database states.
 */
@Service
public class GitPublicationRecoveryBoundaryImpl implements GitPublicationRecoveryBoundary {

    private static final Logger logger = LoggerFactory.getLogger(GitPublicationRecoveryBoundaryImpl.class);

    private final PullRequestPublicationRepository pullRequestPublicationRepository;
    private final HumanApprovalRepository humanApprovalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final GithubCredentialProvider githubCredentialProvider;
    private final GithubApiService githubApiService;
    private final GithubRemoteIdentityVerifier githubRemoteIdentityVerifier;
    private final GitRemotePushBoundary gitRemotePushBoundary;
    private final GitPullRequestCreationBoundary gitPullRequestCreationBoundary;
    private final DurableOrchestratorOwnershipService durableOrchestratorOwnershipService;
    private final AuditLogService auditLogService;

    public GitPublicationRecoveryBoundaryImpl(
            PullRequestPublicationRepository pullRequestPublicationRepository,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            ProjectAuthorizationService projectAuthorizationService,
            GithubCredentialProvider githubCredentialProvider,
            GithubApiService githubApiService,
            GithubRemoteIdentityVerifier githubRemoteIdentityVerifier,
            GitRemotePushBoundary gitRemotePushBoundary,
            GitPullRequestCreationBoundary gitPullRequestCreationBoundary,
            DurableOrchestratorOwnershipService durableOrchestratorOwnershipService,
            AuditLogService auditLogService
    ) {
        this.pullRequestPublicationRepository = pullRequestPublicationRepository;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.githubCredentialProvider = githubCredentialProvider;
        this.githubApiService = githubApiService;
        this.githubRemoteIdentityVerifier = githubRemoteIdentityVerifier;
        this.gitRemotePushBoundary = gitRemotePushBoundary;
        this.gitPullRequestCreationBoundary = gitPullRequestCreationBoundary;
        this.durableOrchestratorOwnershipService = durableOrchestratorOwnershipService;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional
    public GitPullRequestResult recoverOrPublish(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID approvalId,
            User requester
    ) {
        UUID operationId = UUID.randomUUID();

        // 1. Authenticated User & Role Authorization Check
        if (requester == null) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "UNAUTHORIZED", "Authenticated requester required"
            );
        }

        Project project;
        try {
            project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
            projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);
        } catch (ApiException e) {
            logger.warn("Project authorization check failed for user {}: {}", requester.getId(), e.getMessage());
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "AUTHORIZATION_FAILURE", e.getMessage()
            );
        }

        // 2. Precondition & Approval Status Verification
        HumanApproval approval = humanApprovalRepository.findById(approvalId).orElse(null);
        if (approval == null || approval.getStatus() != ApprovalStatus.APPROVED) {
            logger.warn("Approval {} is missing or no longer APPROVED", approvalId);
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_STALE, "APPROVAL_REVOKED", "Human approval status is no longer APPROVED"
            );
        }

        // 3. Binding Relationship Invariant Verification
        if (!approval.getProject().getId().equals(projectId) ||
            !approval.getRepository().getId().equals(repositoryId) ||
            !approval.getTask().getId().equals(taskId) ||
            !approval.getCodeChange().getId().equals(codeChangeId)) {
            logger.warn("Approval relationship binding mismatch for approval {}", approvalId);
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "RELATIONSHIP_MISMATCH", "Approval entity bindings do not match request parameters"
            );
        }

        Repository repository = approval.getRepository();
        Task task = approval.getTask();
        CodeChange codeChange = approval.getCodeChange();

        if (repository.getGithubRepoId() == null || repository.getGithubRepoId().isBlank()) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "REPOSITORY_NOT_LINKED", "Target repository is not linked to GitHub"
            );
        }

        // 4. Redis Fencing Ownership Acquisition
        String fenceToken = durableOrchestratorOwnershipService.acquireOwnership(approvalId, Duration.ofSeconds(60));
        if (fenceToken == null) {
            logger.warn("Durable publication lock acquisition failed for approval {} during recovery", approvalId);
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "FENCING_LOCK_FAILED", "Publication recovery operation is already owned by another worker"
            );
        }

        durableOrchestratorOwnershipService.startHeartbeat(approvalId, fenceToken, Duration.ofSeconds(15), Duration.ofSeconds(60));

        try {
            logAuditAction("PUBLICATION_RECOVERY_STARTED", requester, project, task, approvalId, "N/A", "Recovery process initiated");

            // Re-check TOCTOU approval status
            HumanApproval currentApproval = humanApprovalRepository.findById(approvalId).orElse(approval);
            if (currentApproval.getStatus() != ApprovalStatus.APPROVED) {
                logAuditAction("PUBLICATION_RETRY_BLOCKED", requester, project, task, approvalId, "N/A", "Approval revoked prior to recovery execution");
                return GitPullRequestResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_STALE, "APPROVAL_REVOKED", "Human approval status was revoked prior to recovery execution"
                );
            }

            Optional<PullRequestPublication> pubOpt = pullRequestPublicationRepository.findByApprovalId(approvalId);

            // CASE 1: Publication missing or PENDING -> Start complete workflow from F-C push to F-D PR creation
            if (pubOpt.isEmpty() || pubOpt.get().getPublicationStatus() == PullRequestPublicationStatus.PENDING) {
                return executeFreshPublicationWorkflow(operationId, projectId, repositoryId, taskId, codeChangeId, approvalId, requester);
            }

            PullRequestPublication pub = pubOpt.get();
            PullRequestPublicationStatus status = pub.getPublicationStatus();

            // CASE 2: Terminal Failure States -> Block retries to enforce terminal state immutability
            if (isTerminalFailureState(status)) {
                logAuditAction("PUBLICATION_RETRY_BLOCKED", requester, project, task, approvalId, pub.getBranchName(), "Terminal failure state " + status);
                return GitPullRequestResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        status,
                        pub.getFailureCategory() != null ? pub.getFailureCategory() : "TERMINAL_FAILURE",
                        pub.getFailureReason() != null ? pub.getFailureReason() : "Publication is in terminal failure state " + status
                );
            }

            // CASE 3: Publication is PR_CREATED or PR_RECONCILED -> Verify GitHub state authoritatively & Return Idempotently
            if (status.isPrCreated() && pub.getGithubPrNumber() != null) {
                return verifyAndReturnExistingPr(operationId, pub, requester, project, task, approvalId, fenceToken);
            }

            // CASE 4: Publication is PUSHED or RECONCILED (Push Complete, PR Creation Pending) -> Re-verify branch & proceed to F-D
            if (status == PullRequestPublicationStatus.PUSHED || status == PullRequestPublicationStatus.RECONCILED) {
                return recoverPushedStateToPr(operationId, pub, requester, project, task, approvalId, fenceToken);
            }

            // CASE 5: Publication is PR_CREATING or FAILED_PR_CREATION -> Perform out-of-band PR lookup or proceed
            if (status == PullRequestPublicationStatus.PR_CREATING || status == PullRequestPublicationStatus.FAILED_PR_CREATION) {
                return recoverPrCreationState(operationId, pub, requester, project, task, approvalId, fenceToken);
            }

            // CASE 6: Publication is PUSHING, VERIFYING, or FAILED_PUSH -> Perform out-of-band Branch lookup or re-push
            if (status == PullRequestPublicationStatus.PUSHING || status == PullRequestPublicationStatus.VERIFYING || status == PullRequestPublicationStatus.FAILED_PUSH) {
                return recoverPushState(operationId, pub, requester, project, task, approvalId, fenceToken);
            }

            // Default fallback if unhandled status
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "UNKNOWN_PUBLICATION_STATE", "Unrecognized publication status: " + status
            );

        } finally {
            if (durableOrchestratorOwnershipService.verifyOwnership(approvalId, fenceToken)) {
                durableOrchestratorOwnershipService.stopHeartbeat(approvalId);
                durableOrchestratorOwnershipService.releaseOwnership(approvalId, fenceToken);
            } else {
                logger.error("Stale worker lost durable ownership lock for approval {} during recovery operation. Preserving fence lock.", approvalId);
            }
        }
    }

    private GitPullRequestResult executeFreshPublicationWorkflow(
            UUID operationId, UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId, UUID approvalId, User requester
    ) {
        GitPushResult pushResult = gitRemotePushBoundary.pushApprovedCommitToRemote(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        if (!pushResult.isPushed()) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    pushResult.status(), pushResult.failureCategory(), pushResult.failureReason()
            );
        }

        return gitPullRequestCreationBoundary.createPullRequestFromPublication(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );
    }

    private GitPullRequestResult verifyAndReturnExistingPr(
            UUID operationId, PullRequestPublication pub, User requester, Project project, Task task, UUID approvalId, String fenceToken
    ) {
        String ownerName = pub.getRepository().getOwnerName();
        String repoName = pub.getRepository().getRepoName();
        String branchName = pub.getBranchName();
        String baseBranch = pub.getBaseBranch();
        String commitSha = pub.getCommitSha();
        String expectedGithubRepoId = pub.getGithubRepoId();
        Long expectedPrNumber = pub.getGithubPrNumber();

        try (GithubCredentialContext cred = githubCredentialProvider.getGithubCredential(
                project.getId(), pub.getRepository().getId(), task.getId(), approvalId, requester)) {

            List<GithubPullRequestDto> matchingPrs = githubApiService.findPullRequestsByHead(
                    cred.getToken(), ownerName, repoName, branchName, baseBranch
            );

            List<GithubPullRequestDto> validPrs = filterMatchingPrs(matchingPrs, branchName, commitSha, baseBranch, expectedGithubRepoId, ownerName, repoName);

            if (validPrs.size() == 1 && validPrs.get(0).getNumber().equals(expectedPrNumber)) {
                logAuditAction("PUBLICATION_RECONCILED", requester, project, task, approvalId, branchName, "Authoritatively verified existing PR #" + expectedPrNumber);
                return GitPullRequestResult.success(
                        operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                        pub.getGithubPrNumber(), pub.getPrUrl(), branchName, baseBranch, commitSha,
                        "Pull Request #" + pub.getGithubPrNumber(), pub.getPublicationStatus()
                );
            } else if (matchingPrs == null || matchingPrs.isEmpty()) {
                logger.warn("Authoritative PR verification failed for approval {}: PR #{} not found on GitHub", approvalId, expectedPrNumber);
                logAuditAction("PUBLICATION_RECOVERY_FAILED", requester, project, task, approvalId, branchName, "PR #" + expectedPrNumber + " missing on GitHub");
                return GitPullRequestResult.failure(
                        operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                        PullRequestPublicationStatus.FAILED_PR_VERIFICATION, "PR_NOT_FOUND_ON_GITHUB", "Expected PR #" + expectedPrNumber + " not found on GitHub"
                );
            } else {
                logger.warn("Authoritative PR identity mismatch for approval {}: matching PR count={}", approvalId, validPrs.size());
                logAuditAction("PUBLICATION_RECOVERY_FAILED", requester, project, task, approvalId, branchName, "PR identity mismatch on GitHub");
                return GitPullRequestResult.failure(
                        operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                        PullRequestPublicationStatus.FAILED_PR_VERIFICATION, "PR_IDENTITY_MISMATCH", "Authoritative PR identity verification failed"
                );
            }

        } catch (ApiException e) {
            logger.error("GitHub API error during authoritative PR verification for approval {}: {}", approvalId, e.getMessage());
            logAuditAction("PUBLICATION_RECOVERY_FAILED", requester, project, task, approvalId, branchName, "GitHub API error: " + e.getMessage());
            return GitPullRequestResult.failure(
                    operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "UNKNOWN_EXTERNAL_STATE", "GitHub API error during verification: " + e.getMessage()
            );
        }
    }

    private GitPullRequestResult recoverPushedStateToPr(
            UUID operationId, PullRequestPublication pub, User requester, Project project, Task task, UUID approvalId, String fenceToken
    ) {
        String ownerName = pub.getRepository().getOwnerName();
        String repoName = pub.getRepository().getRepoName();
        String branchName = pub.getBranchName();
        String commitSha = pub.getCommitSha();

        try (GithubCredentialContext cred = githubCredentialProvider.getGithubCredential(
                project.getId(), pub.getRepository().getId(), task.getId(), approvalId, requester)) {

            BranchDto remoteBranch = null;
            try {
                remoteBranch = githubApiService.fetchBranchDetails(cred.getToken(), ownerName, repoName, branchName);
            } catch (ApiException e) {
                if (e.getStatus() != HttpStatus.NOT_FOUND) {
                    savePublicationPrState(pub, PullRequestPublicationStatus.FAILED_PUSH, null, null, "UNKNOWN_EXTERNAL_STATE", "GitHub API error verifying remote branch: " + e.getMessage(), fenceToken, approvalId);
                    logAuditAction("PUBLICATION_RECOVERY_FAILED", requester, project, task, approvalId, branchName, "Branch query error: " + e.getMessage());
                    return GitPullRequestResult.failure(
                            operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                            PullRequestPublicationStatus.FAILED_PUSH, "UNKNOWN_EXTERNAL_STATE", "GitHub API error verifying remote branch: " + e.getMessage()
                    );
                }
            }

            if (remoteBranch == null || remoteBranch.getCommitSha() == null) {
                // Remote branch lost on GitHub -> re-push then PR
                logger.info("Remote branch {} missing on GitHub during recovery. Re-executing push.", branchName);
                return executeFreshPublicationWorkflow(operationId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(), approvalId, requester);
            }

            if (!remoteBranch.getCommitSha().trim().equalsIgnoreCase(commitSha)) {
                // Remote SHA conflict
                savePublicationPrState(pub, PullRequestPublicationStatus.FAILED_CONFLICT, null, null, "REMOTE_BRANCH_CONFLICT", "Remote branch commit SHA mismatch: expected " + commitSha + ", got " + remoteBranch.getCommitSha(), fenceToken, approvalId);
                logAuditAction("PUBLICATION_CONFLICT", requester, project, task, approvalId, branchName, "Remote branch SHA conflict");
                return GitPullRequestResult.failure(
                        operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                        PullRequestPublicationStatus.FAILED_CONFLICT, "REMOTE_BRANCH_CONFLICT", "Remote branch commit SHA mismatch: expected " + commitSha + ", got " + remoteBranch.getCommitSha()
                );
            }

            // Remote branch verified -> proceed to F-D PR Creation
            return gitPullRequestCreationBoundary.createPullRequestFromPublication(
                    approvalId, operationId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(), requester
            );
        }
    }

    private GitPullRequestResult recoverPrCreationState(
            UUID operationId, PullRequestPublication pub, User requester, Project project, Task task, UUID approvalId, String fenceToken
    ) {
        String ownerName = pub.getRepository().getOwnerName();
        String repoName = pub.getRepository().getRepoName();
        String branchName = pub.getBranchName();
        String baseBranch = pub.getBaseBranch();
        String commitSha = pub.getCommitSha();
        String expectedGithubRepoId = pub.getGithubRepoId();

        try (GithubCredentialContext cred = githubCredentialProvider.getGithubCredential(
                project.getId(), pub.getRepository().getId(), task.getId(), approvalId, requester)) {

            List<GithubPullRequestDto> matchingPrs = githubApiService.findPullRequestsByHead(
                    cred.getToken(), ownerName, repoName, branchName, baseBranch
            );

            List<GithubPullRequestDto> validPrs = filterMatchingPrs(matchingPrs, branchName, commitSha, baseBranch, expectedGithubRepoId, ownerName, repoName);

            if (validPrs.size() == 1) {
                GithubPullRequestDto pr = validPrs.get(0);
                savePublicationPrState(pub, PullRequestPublicationStatus.PR_RECONCILED, pr.getNumber(), pr.getHtmlUrl(), null, null, fenceToken, approvalId);
                logAuditAction("PUBLICATION_RECONCILED", requester, project, task, approvalId, branchName, "Recovered existing PR #" + pr.getNumber());
                return GitPullRequestResult.success(
                        operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                        pr.getNumber(), pr.getHtmlUrl(), branchName, baseBranch, commitSha, "Pull Request #" + pr.getNumber(), PullRequestPublicationStatus.PR_RECONCILED
                );
            } else if (validPrs.size() > 1) {
                savePublicationPrState(pub, PullRequestPublicationStatus.FAILED_PR_CONFLICT, null, null, "MULTIPLE_MATCHING_PRS", "Found " + validPrs.size() + " matching PRs on GitHub", fenceToken, approvalId);
                logAuditAction("PUBLICATION_CONFLICT", requester, project, task, approvalId, branchName, "Multiple matching PRs found");
                return GitPullRequestResult.failure(
                        operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                        PullRequestPublicationStatus.FAILED_PR_CONFLICT, "MULTIPLE_MATCHING_PRS", "Found " + validPrs.size() + " matching PRs on GitHub"
                );
            } else {
                // 0 matching PRs -> Safe to proceed with F-D PR creation
                return gitPullRequestCreationBoundary.createPullRequestFromPublication(
                        approvalId, operationId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(), requester
                );
            }

        } catch (ApiException e) {
            savePublicationPrState(pub, PullRequestPublicationStatus.FAILED_PR_CREATION, null, null, "UNKNOWN_EXTERNAL_STATE", "GitHub API error querying PR state: " + e.getMessage(), fenceToken, approvalId);
            logAuditAction("PUBLICATION_RECOVERY_FAILED", requester, project, task, approvalId, branchName, "GitHub API error: " + e.getMessage());
            return GitPullRequestResult.failure(
                    operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "UNKNOWN_EXTERNAL_STATE", "GitHub API error querying PR state: " + e.getMessage()
            );
        }
    }

    private GitPullRequestResult recoverPushState(
            UUID operationId, PullRequestPublication pub, User requester, Project project, Task task, UUID approvalId, String fenceToken
    ) {
        String ownerName = pub.getRepository().getOwnerName();
        String repoName = pub.getRepository().getRepoName();
        String branchName = pub.getBranchName();
        String commitSha = pub.getCommitSha();

        try (GithubCredentialContext cred = githubCredentialProvider.getGithubCredential(
                project.getId(), pub.getRepository().getId(), task.getId(), approvalId, requester)) {

            BranchDto remoteBranch = null;
            try {
                remoteBranch = githubApiService.fetchBranchDetails(cred.getToken(), ownerName, repoName, branchName);
            } catch (ApiException e) {
                if (e.getStatus() != HttpStatus.NOT_FOUND) {
                    savePublicationPrState(pub, PullRequestPublicationStatus.FAILED_PUSH, null, null, "UNKNOWN_EXTERNAL_STATE", "GitHub API error verifying remote branch: " + e.getMessage(), fenceToken, approvalId);
                    logAuditAction("PUBLICATION_RECOVERY_FAILED", requester, project, task, approvalId, branchName, "GitHub API error: " + e.getMessage());
                    return GitPullRequestResult.failure(
                            operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                            PullRequestPublicationStatus.FAILED_PUSH, "UNKNOWN_EXTERNAL_STATE", "GitHub API error verifying remote branch: " + e.getMessage()
                    );
                }
            }

            if (remoteBranch != null && remoteBranch.getCommitSha() != null) {
                if (remoteBranch.getCommitSha().trim().equalsIgnoreCase(commitSha)) {
                    savePublicationPrState(pub, PullRequestPublicationStatus.RECONCILED, null, null, null, null, fenceToken, approvalId);
                    logAuditAction("PUBLICATION_RECONCILED", requester, project, task, approvalId, branchName, "Reconciled remote branch at commit " + commitSha);
                    return gitPullRequestCreationBoundary.createPullRequestFromPublication(
                            approvalId, operationId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(), requester
                    );
                } else {
                    savePublicationPrState(pub, PullRequestPublicationStatus.FAILED_CONFLICT, null, null, "REMOTE_BRANCH_CONFLICT", "Remote branch commit SHA mismatch", fenceToken, approvalId);
                    logAuditAction("PUBLICATION_CONFLICT", requester, project, task, approvalId, branchName, "Remote branch SHA conflict");
                    return GitPullRequestResult.failure(
                            operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                            PullRequestPublicationStatus.FAILED_CONFLICT, "REMOTE_BRANCH_CONFLICT", "Remote branch commit SHA mismatch"
                    );
                }
            }

            // Remote branch 404 -> Safe to execute push then PR creation
            return executeFreshPublicationWorkflow(operationId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(), approvalId, requester);

        } catch (Exception e) {
            savePublicationPrState(pub, PullRequestPublicationStatus.FAILED_PUSH, null, null, "UNKNOWN_EXTERNAL_STATE", "Error during push recovery: " + e.getMessage(), fenceToken, approvalId);
            logAuditAction("PUBLICATION_RECOVERY_FAILED", requester, project, task, approvalId, branchName, "Push recovery error: " + e.getMessage());
            return GitPullRequestResult.failure(
                    operationId, approvalId, project.getId(), pub.getRepository().getId(), task.getId(), pub.getCodeChange().getId(),
                    PullRequestPublicationStatus.FAILED_PUSH, "UNKNOWN_EXTERNAL_STATE", "Error during push recovery: " + e.getMessage()
            );
        }
    }

    private List<GithubPullRequestDto> filterMatchingPrs(
            List<GithubPullRequestDto> prs, String expectedBranch, String expectedSha, String expectedBase,
            String expectedGithubRepoId, String expectedOwner, String expectedRepoName
    ) {
        if (prs == null || prs.isEmpty()) {
            return List.of();
        }
        return prs.stream().filter(pr -> {
            if (pr.getHead() == null || pr.getHead().getRef() == null || pr.getHead().getSha() == null) {
                return false;
            }
            if (!pr.getHead().getRef().equals(expectedBranch)) {
                return false;
            }
            if (!pr.getHead().getSha().equalsIgnoreCase(expectedSha)) {
                return false;
            }
            if (pr.getBase() != null && pr.getBase().getRef() != null) {
                if (!pr.getBase().getRef().equals(expectedBase)) {
                    return false;
                }
            }
            if (pr.getHead().getRepo() != null) {
                if (pr.getHead().getRepo().getId() != null) {
                    if (!String.valueOf(pr.getHead().getRepo().getId()).equals(expectedGithubRepoId)) {
                        return false;
                    }
                }
                if (pr.getHead().getRepo().getOwner() != null && pr.getHead().getRepo().getOwner().getLogin() != null) {
                    if (!pr.getHead().getRepo().getOwner().getLogin().equalsIgnoreCase(expectedOwner)) {
                        return false;
                    }
                }
                if (pr.getHead().getRepo().getName() != null) {
                    if (!pr.getHead().getRepo().getName().equalsIgnoreCase(expectedRepoName)) {
                        return false;
                    }
                }
            }
            if (pr.getBase() != null && pr.getBase().getRepo() != null) {
                if (pr.getBase().getRepo().getId() != null) {
                    if (!String.valueOf(pr.getBase().getRepo().getId()).equals(expectedGithubRepoId)) {
                        return false;
                    }
                }
                if (pr.getBase().getRepo().getOwner() != null && pr.getBase().getRepo().getOwner().getLogin() != null) {
                    if (!pr.getBase().getRepo().getOwner().getLogin().equalsIgnoreCase(expectedOwner)) {
                        return false;
                    }
                }
                if (pr.getBase().getRepo().getName() != null) {
                    if (!pr.getBase().getRepo().getName().equalsIgnoreCase(expectedRepoName)) {
                        return false;
                    }
                }
            }
            return true;
        }).toList();
    }

    private boolean isTerminalFailureState(PullRequestPublicationStatus status) {
        return status == PullRequestPublicationStatus.FAILED_STALE ||
               status == PullRequestPublicationStatus.FAILED_CONFLICT ||
               status == PullRequestPublicationStatus.FAILED_VERIFICATION ||
               status == PullRequestPublicationStatus.FAILED_PR_CONFLICT ||
               status == PullRequestPublicationStatus.FAILED_PR_VERIFICATION;
    }

    private void savePublicationPrState(
            PullRequestPublication pub,
            PullRequestPublicationStatus newStatus,
            Long prNumber,
            String prUrl,
            String failureCategory,
            String failureReason,
            String fenceToken,
            UUID approvalId
    ) {
        if (!durableOrchestratorOwnershipService.verifyOwnership(approvalId, fenceToken)) {
            logger.error("Stale worker lost durable ownership lock for approval {} during recovery save. Aborting DB write.", approvalId);
            throw new IllegalStateException("Durable fencing ownership lost to another worker during recovery state transition");
        }

        validateStateTransition(pub.getPublicationStatus(), newStatus);
        pub.setPublicationStatus(newStatus);
        if (prNumber != null) pub.setGithubPrNumber(prNumber);
        if (prUrl != null) pub.setPrUrl(prUrl);
        if (failureCategory != null) pub.setFailureCategory(failureCategory);
        if (failureReason != null) pub.setFailureReason(failureReason);

        pullRequestPublicationRepository.saveAndFlush(pub);
    }

    void validateStateTransition(PullRequestPublicationStatus currentStatus, PullRequestPublicationStatus newStatus) {
        if (currentStatus == null || currentStatus == newStatus) {
            return;
        }

        if (isTerminalFailureState(currentStatus)) {
            throw new IllegalStateException("Illegal state machine transition: terminal failure state " + currentStatus + " cannot transition to " + newStatus);
        }

        boolean isLegal = false;
        switch (currentStatus) {
            case PENDING:
                isLegal = (newStatus == PullRequestPublicationStatus.VERIFYING ||
                           newStatus == PullRequestPublicationStatus.PUSHING ||
                           newStatus == PullRequestPublicationStatus.FAILED_STALE ||
                           newStatus == PullRequestPublicationStatus.FAILED_VERIFICATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_PUSH);
                break;
            case VERIFYING:
                isLegal = (newStatus == PullRequestPublicationStatus.PUSHING ||
                           newStatus == PullRequestPublicationStatus.RECONCILED ||
                           newStatus == PullRequestPublicationStatus.FAILED_STALE ||
                           newStatus == PullRequestPublicationStatus.FAILED_CONFLICT ||
                           newStatus == PullRequestPublicationStatus.FAILED_VERIFICATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_PUSH);
                break;
            case PUSHING:
                isLegal = (newStatus == PullRequestPublicationStatus.PUSHED ||
                           newStatus == PullRequestPublicationStatus.RECONCILED ||
                           newStatus == PullRequestPublicationStatus.FAILED_PUSH ||
                           newStatus == PullRequestPublicationStatus.FAILED_VERIFICATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_CONFLICT ||
                           newStatus == PullRequestPublicationStatus.FAILED_STALE);
                break;
            case PUSHED:
            case RECONCILED:
                isLegal = (newStatus == PullRequestPublicationStatus.PR_CREATING ||
                           newStatus == PullRequestPublicationStatus.PR_RECONCILED ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CREATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CONFLICT ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_VERIFICATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_STALE);
                break;
            case PR_CREATING:
                isLegal = (newStatus == PullRequestPublicationStatus.PR_CREATED ||
                           newStatus == PullRequestPublicationStatus.PR_RECONCILED ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CREATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CONFLICT ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_VERIFICATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_STALE);
                break;
            case FAILED_PUSH:
                isLegal = (newStatus == PullRequestPublicationStatus.RECONCILED ||
                           newStatus == PullRequestPublicationStatus.PUSHING);
                break;
            case FAILED_PR_CREATION:
                isLegal = (newStatus == PullRequestPublicationStatus.PR_RECONCILED ||
                           newStatus == PullRequestPublicationStatus.PR_CREATING);
                break;
            default:
                isLegal = false;
                break;
        }

        if (!isLegal) {
            throw new IllegalStateException("Illegal state machine transition: " + currentStatus + " -> " + newStatus);
        }
    }

    private void logAuditAction(String action, User requester, Project project, Task task, UUID approvalId, String branchName, String detail) {
        auditLogService.logAction(
                requester, project, task,
                action, "HUMAN_APPROVAL", approvalId,
                "{\"branch\":\"" + branchName + "\",\"detail\":\"" + detail + "\"}"
        );
    }
}
