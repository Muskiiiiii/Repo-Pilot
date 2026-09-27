package com.repopilot.approval.service;

import com.repopilot.approval.dto.GitPullRequestResult;
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
import com.repopilot.github.dto.GithubRepoDto;
import com.repopilot.github.security.GithubCredentialContext;
import com.repopilot.github.security.GithubCredentialProvider;
import com.repopilot.github.security.SecretRedactor;
import com.repopilot.github.service.GithubApiService;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.pullrequest.entity.PullRequest;
import com.repopilot.pullrequest.repository.PullRequestRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.service.DurableOrchestratorOwnershipService;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

@Service
public class GitPullRequestCreationBoundaryImpl implements GitPullRequestCreationBoundary {

    private static final Logger logger = LoggerFactory.getLogger(GitPullRequestCreationBoundaryImpl.class);

    private final PullRequestPublicationRepository pullRequestPublicationRepository;
    private final PullRequestRepository pullRequestRepository;
    private final HumanApprovalRepository humanApprovalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final GithubApiService githubApiService;
    private final GithubCredentialProvider githubCredentialProvider;
    private final GithubRemoteIdentityVerifier githubRemoteIdentityVerifier;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final DurableOrchestratorOwnershipService durableOrchestratorOwnershipService;
    private final AuditLogService auditLogService;

    @Autowired
    public GitPullRequestCreationBoundaryImpl(
            PullRequestPublicationRepository pullRequestPublicationRepository,
            PullRequestRepository pullRequestRepository,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            GithubApiService githubApiService,
            GithubCredentialProvider githubCredentialProvider,
            GithubRemoteIdentityVerifier githubRemoteIdentityVerifier,
            ProjectAuthorizationService projectAuthorizationService,
            DurableOrchestratorOwnershipService durableOrchestratorOwnershipService,
            AuditLogService auditLogService
    ) {
        this.pullRequestPublicationRepository = pullRequestPublicationRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.githubApiService = githubApiService;
        this.githubCredentialProvider = githubCredentialProvider;
        this.githubRemoteIdentityVerifier = githubRemoteIdentityVerifier;
        this.projectAuthorizationService = projectAuthorizationService;
        this.durableOrchestratorOwnershipService = durableOrchestratorOwnershipService;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(noRollbackFor = {IllegalStateException.class, ApiException.class})
    public GitPullRequestResult createPullRequestFromPublication(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    ) {
        // 1. Mandatory Input Null Checks
        if (approvalId == null || operationId == null || projectId == null ||
            repositoryId == null || taskId == null || codeChangeId == null || requester == null) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "INVALID_INPUT", "All parameters must be non-null"
            );
        }

        // 2. Project Authorization Check
        Project project;
        try {
            project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
            projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);
        } catch (ApiException e) {
            logger.warn("Project authorization check failed for user {}: {}", requester.getId(), e.getMessage());
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "UNAUTHORIZED", e.getMessage()
            );
        }

        // 3. Approval Precondition Lookup & Status Check
        Optional<HumanApproval> approvalOpt = humanApprovalRepository.findById(approvalId);
        if (approvalOpt.isEmpty()) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "APPROVAL_NOT_FOUND", "Human approval record not found"
            );
        }

        HumanApproval approval = approvalOpt.get();
        if (approval.getStatus() != ApprovalStatus.APPROVED) {
            logger.warn("Approval TOCTOU rejection: approval {} status is {}", approvalId, approval.getStatus());
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_STALE, "APPROVAL_REVOKED", "Human approval status is no longer APPROVED"
            );
        }

        // 4. Publication Lookup & Invariant Checks
        Optional<PullRequestPublication> pubOpt = pullRequestPublicationRepository.findByApprovalId(approvalId);
        if (pubOpt.isEmpty()) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "PUBLICATION_NOT_FOUND", "No F-C publication record found for approval"
            );
        }

        PullRequestPublication publication = pubOpt.get();

        // Relationship Binding Invariant Validation
        if (!publication.getProject().getId().equals(projectId) ||
            !publication.getRepository().getId().equals(repositoryId) ||
            !publication.getTask().getId().equals(taskId) ||
            !publication.getCodeChange().getId().equals(codeChangeId)) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "RELATIONSHIP_MISMATCH", "Publication entities do not match request IDs"
            );
        }

        // Publication Status Validation (Must represent successful remote publication)
        if (!publication.getPublicationStatus().isPushed()) {
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "INVALID_PUBLICATION_STATE", "Publication is not in a valid published state: " + publication.getPublicationStatus()
            );
        }

        // 5. Idempotent Return for Already Created PR
        if (publication.getPublicationStatus().isPrCreated() && publication.getGithubPrNumber() != null) {
            logger.info("Publication for approval {} already has PR #{} created (idempotent skip)", approvalId, publication.getGithubPrNumber());
            return GitPullRequestResult.success(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    publication.getGithubPrNumber(), publication.getPrUrl(),
                    publication.getBranchName(), publication.getBaseBranch(),
                    publication.getCommitSha(), "Pull Request #" + publication.getGithubPrNumber(),
                    publication.getPublicationStatus()
            );
        }

        // 6. Redis Fencing Ownership Acquisition
        String fenceToken = durableOrchestratorOwnershipService.acquireOwnership(approvalId, Duration.ofSeconds(60));
        if (fenceToken == null) {
            logger.warn("Durable publication lock acquisition failed for approval {} (already owned or lock service down)", approvalId);
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "FENCING_LOCK_FAILED", "PR creation operation is already owned by another worker or lock service unavailable"
            );
        }

        durableOrchestratorOwnershipService.startHeartbeat(approvalId, fenceToken, Duration.ofSeconds(15), Duration.ofSeconds(60));

        try {
            // Re-verify approval status after lock acquisition (TOCTOU guard)
            HumanApproval currentApproval = humanApprovalRepository.findById(approvalId).orElse(approval);
            if (currentApproval.getStatus() != ApprovalStatus.APPROVED) {
                return GitPullRequestResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_STALE, "APPROVAL_REVOKED", "Human approval status was revoked prior to PR creation"
                );
            }

            Repository repository = publication.getRepository();
            Task task = publication.getTask();
            CodeChange codeChange = publication.getCodeChange();
            String ownerName = repository.getOwnerName();
            String repoName = repository.getRepoName();

            // 7. Authoritative Pre-Creation Remote Branch & Repository Verification
            try (GithubCredentialContext cred = githubCredentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
                String token = cred.getToken();

                // Verify Repository Identity
                GithubRemoteVerificationResult identityResult = githubRemoteIdentityVerifier.verifyGithubRemoteIdentity(
                        projectId, repositoryId, taskId, codeChangeId, approvalId, requester, token
                );
                if (identityResult != null && !identityResult.isVerified()) {
                    savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_VERIFICATION, "REMOTE_IDENTITY_FAILED", identityResult.failureReason(), fenceToken);
                    logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "REMOTE_IDENTITY_FAILED");
                    return GitPullRequestResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_VERIFICATION, "REMOTE_IDENTITY_FAILED", identityResult.failureReason()
                    );
                }

                // Verify Remote Branch Tip
                BranchDto branchDetails;
                try {
                    branchDetails = githubApiService.fetchBranchDetails(token, ownerName, repoName, publication.getBranchName());
                } catch (ApiException e) {
                    savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_VERIFICATION, "REMOTE_BRANCH_NOT_FOUND", "Remote branch not found on GitHub: " + e.getMessage(), fenceToken);
                    logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "REMOTE_BRANCH_NOT_FOUND");
                    return GitPullRequestResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_VERIFICATION, "REMOTE_BRANCH_NOT_FOUND", "Remote branch not found on GitHub: " + publication.getBranchName()
                    );
                }

                if (branchDetails == null || branchDetails.getCommitSha() == null ||
                    !branchDetails.getCommitSha().trim().equalsIgnoreCase(publication.getCommitSha())) {
                    String actualSha = (branchDetails != null) ? branchDetails.getCommitSha() : "null";
                    savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_VERIFICATION, "BRANCH_TIP_MISMATCH", "Remote SHA " + actualSha + " does not match expected " + publication.getCommitSha(), fenceToken);
                    logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "BRANCH_TIP_MISMATCH");
                    return GitPullRequestResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_VERIFICATION, "BRANCH_TIP_MISMATCH", "Remote SHA " + actualSha + " does not match expected " + publication.getCommitSha()
                    );
                }

                // Verify Authoritative Default Branch
                GithubRepoDto repoMetadata = githubApiService.fetchRepositoryMetadata(token, ownerName, repoName);
                if (repoMetadata == null || repoMetadata.getDefaultBranch() == null ||
                    !repoMetadata.getDefaultBranch().trim().equalsIgnoreCase(publication.getBaseBranch())) {
                    String actualDefault = (repoMetadata != null) ? repoMetadata.getDefaultBranch() : "null";
                    savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_VERIFICATION, "BASE_BRANCH_MISMATCH", "Authoritative default branch " + actualDefault + " does not match base branch " + publication.getBaseBranch(), fenceToken);
                    logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "BASE_BRANCH_MISMATCH");
                    return GitPullRequestResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_VERIFICATION, "BASE_BRANCH_MISMATCH", "Authoritative default branch " + actualDefault + " does not match " + publication.getBaseBranch()
                    );
                }

                // 8. Pre-POST Duplicate PR Lookup on GitHub (Requirement 6 & 7)
                List<GithubPullRequestDto> existingPrs = githubApiService.findPullRequestsByHead(token, ownerName, repoName, publication.getBranchName(), publication.getBaseBranch());
                List<GithubPullRequestDto> matchingPrs = filterMatchingPrs(existingPrs, publication.getBranchName(), publication.getBaseBranch(), ownerName, repoName, publication.getGithubRepoId());

                if (matchingPrs.size() == 1) {
                    GithubPullRequestDto existingPr = matchingPrs.get(0);
                    if (verifyPrIdentity(existingPr, publication.getBranchName(), publication.getCommitSha(), publication.getBaseBranch(), ownerName, repoName, publication.getGithubRepoId())) {
                        savePublicationPrState(publication, PullRequestPublicationStatus.PR_RECONCILED, null, null, existingPr.getNumber(), existingPr.getHtmlUrl(), fenceToken);
                        saveOrUpdatePullRequestEntity(task, repository, existingPr.getNumber(), publication.getBranchName(), publication.getBaseBranch(), publication.getCommitSha(), existingPr.getTitle(), existingPr.getBody(), existingPr.getHtmlUrl());
                        logAuditAction("PR_RECONCILED", requester, project, task, approvalId, publication.getBranchName(), "EXISTING_PR_RECONCILED");

                        return GitPullRequestResult.success(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                existingPr.getNumber(), existingPr.getHtmlUrl(),
                                publication.getBranchName(), publication.getBaseBranch(),
                                publication.getCommitSha(), existingPr.getTitle(),
                                PullRequestPublicationStatus.PR_RECONCILED
                        );
                    } else {
                        savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_PR_VERIFICATION, "PR_IDENTITY_MISMATCH", "Existing PR identity verification failed", fenceToken);
                        logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "PR_IDENTITY_MISMATCH");
                        return GitPullRequestResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                PullRequestPublicationStatus.FAILED_PR_VERIFICATION, "PR_IDENTITY_MISMATCH", "Existing PR identity verification failed"
                        );
                    }
                } else if (matchingPrs.size() > 1) {
                    savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_PR_CONFLICT, "MULTIPLE_MATCHING_PRS", "Multiple matching PRs found on remote repository", fenceToken);
                    logAuditAction("PR_CREATION_CONFLICT", requester, project, task, approvalId, publication.getBranchName(), "MULTIPLE_MATCHING_PRS");
                    return GitPullRequestResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_PR_CONFLICT, "MULTIPLE_MATCHING_PRS", "Multiple matching PRs found on remote repository"
                    );
                }

                // 9. Update DB State to PR_CREATING
                savePublicationPrState(publication, PullRequestPublicationStatus.PR_CREATING, null, null, fenceToken);
                logAuditAction("PR_CREATION_STARTED", requester, project, task, approvalId, publication.getBranchName(), "START_POST");

                // 10. Construct Server-Side Bounded PR Content
                String prTitle = sanitizeTitle(task.getTitle());
                String prBody = sanitizeBody(codeChange != null ? codeChange.getReason() : null, publication.getBranchName(), publication.getCommitSha(), cred.getTokenChars());

                // 11. Execute GitHub POST /pulls with Out-of-Band Crash Recovery (Requirement 7)
                GithubPullRequestDto createdPr = null;
                boolean isReconciled = false;
                try {
                    createdPr = githubApiService.createPullRequest(token, ownerName, repoName, publication.getBranchName(), publication.getBaseBranch(), prTitle, prBody);
                } catch (Exception postEx) {
                    logger.warn("GitHub POST /pulls failed or timed out for branch {}: {}", publication.getBranchName(), postEx.getMessage());

                    // Out-Of-Band Recovery Query
                    List<GithubPullRequestDto> recoveryPrs;
                    try {
                        recoveryPrs = githubApiService.findPullRequestsByHead(token, ownerName, repoName, publication.getBranchName(), publication.getBaseBranch());
                    } catch (Exception recEx) {
                        logger.error("GitHub PR recovery query failed after failed POST for branch {}: {}", publication.getBranchName(), recEx.getMessage());
                        savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_PR_CREATION, "UNKNOWN_EXTERNAL_STATE", "GitHub POST failed and recovery query failed: " + postEx.getMessage(), fenceToken);
                        logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "UNKNOWN_EXTERNAL_STATE");
                        return GitPullRequestResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                PullRequestPublicationStatus.FAILED_PR_CREATION, "UNKNOWN_EXTERNAL_STATE", "GitHub POST failed and recovery query failed"
                        );
                    }

                    List<GithubPullRequestDto> matchingRecovery = filterMatchingPrs(recoveryPrs, publication.getBranchName(), publication.getBaseBranch(), ownerName, repoName, publication.getGithubRepoId());
                    if (matchingRecovery.size() == 1) {
                        createdPr = matchingRecovery.get(0);
                        isReconciled = true;
                        logger.info("Successfully recovered created PR #{} via REST API inquiry for branch {}", createdPr.getNumber(), publication.getBranchName());
                    } else if (matchingRecovery.size() > 1) {
                        savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_PR_CONFLICT, "MULTIPLE_MATCHING_PRS", "Multiple matching PRs found during crash recovery", fenceToken);
                        logAuditAction("PR_CREATION_CONFLICT", requester, project, task, approvalId, publication.getBranchName(), "MULTIPLE_MATCHING_PRS");
                        return GitPullRequestResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                PullRequestPublicationStatus.FAILED_PR_CONFLICT, "MULTIPLE_MATCHING_PRS", "Multiple matching PRs found during recovery"
                        );
                    } else {
                        savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_PR_CREATION, "POST_FAILED", "GitHub POST /pulls failed: " + postEx.getMessage(), fenceToken);
                        logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "POST_FAILED");
                        return GitPullRequestResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                PullRequestPublicationStatus.FAILED_PR_CREATION, "POST_FAILED", "GitHub POST /pulls failed: " + postEx.getMessage()
                        );
                    }
                }

                // 12. Post-Creation PR Identity Verification (Requirement 10)
                if (!verifyPrIdentity(createdPr, publication.getBranchName(), publication.getCommitSha(), publication.getBaseBranch(), ownerName, repoName, publication.getGithubRepoId())) {
                    savePublicationPrState(publication, PullRequestPublicationStatus.FAILED_PR_VERIFICATION, "PR_IDENTITY_MISMATCH", "PR identity verification failed for created PR", fenceToken);
                    logAuditAction("PR_CREATION_FAILED", requester, project, task, approvalId, publication.getBranchName(), "PR_IDENTITY_MISMATCH");
                    return GitPullRequestResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_PR_VERIFICATION, "PR_IDENTITY_MISMATCH", "PR identity verification failed for created PR"
                    );
                }

                // 13. Save Final Durable State & Emit Audit Signature
                PullRequestPublicationStatus finalStatus = isReconciled ? PullRequestPublicationStatus.PR_RECONCILED : PullRequestPublicationStatus.PR_CREATED;
                savePublicationPrState(publication, finalStatus, null, null, createdPr.getNumber(), createdPr.getHtmlUrl(), fenceToken);
                saveOrUpdatePullRequestEntity(task, repository, createdPr.getNumber(), publication.getBranchName(), publication.getBaseBranch(), publication.getCommitSha(), prTitle, prBody, createdPr.getHtmlUrl());

                String auditAction = isReconciled ? "PR_RECONCILED" : "PR_CREATED";
                logAuditAction(auditAction, requester, project, task, approvalId, publication.getBranchName(), "SUCCESS_PR_" + createdPr.getNumber());

                return GitPullRequestResult.success(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        createdPr.getNumber(), createdPr.getHtmlUrl(),
                        publication.getBranchName(), publication.getBaseBranch(),
                        publication.getCommitSha(), prTitle,
                        finalStatus
                );
            }
        } catch (IllegalStateException e) {
            logger.error("State transition or fencing error during PR creation for approval {}: {}", approvalId, e.getMessage());
            return GitPullRequestResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PR_CREATION, "FENCING_LOST", e.getMessage()
            );
        } finally {
            if (durableOrchestratorOwnershipService.verifyOwnership(approvalId, fenceToken)) {
                durableOrchestratorOwnershipService.stopHeartbeat(approvalId);
                durableOrchestratorOwnershipService.releaseOwnership(approvalId, fenceToken);
            } else {
                logger.warn("Worker fence token {} lost ownership for approval {} - leaving active owner lock untouched in finally block.", fenceToken, approvalId);
            }
        }
    }

    void validateStateTransition(PullRequestPublicationStatus currentStatus, PullRequestPublicationStatus newStatus) {
        if (currentStatus == null || currentStatus == newStatus) {
            return;
        }

        // Terminal PR states cannot transition to any different state (except FAILED_PR_CREATION -> PR_RECONCILED for recovery)
        boolean isTerminalPrState = (currentStatus == PullRequestPublicationStatus.PR_CREATED ||
                                     currentStatus == PullRequestPublicationStatus.PR_RECONCILED ||
                                     currentStatus == PullRequestPublicationStatus.FAILED_PR_CONFLICT ||
                                     currentStatus == PullRequestPublicationStatus.FAILED_PR_VERIFICATION);
        if (isTerminalPrState) {
            throw new IllegalStateException("Illegal state machine transition: terminal PR state " + currentStatus + " cannot transition to " + newStatus);
        }

        boolean isLegal = false;
        switch (currentStatus) {
            case PUSHED:
            case RECONCILED:
                isLegal = (newStatus == PullRequestPublicationStatus.PR_CREATING ||
                           newStatus == PullRequestPublicationStatus.PR_RECONCILED ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CREATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CONFLICT ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_VERIFICATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_STALE ||
                           newStatus == PullRequestPublicationStatus.FAILED_VERIFICATION);
                break;
            case PR_CREATING:
                isLegal = (newStatus == PullRequestPublicationStatus.PR_CREATED ||
                           newStatus == PullRequestPublicationStatus.PR_RECONCILED ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CREATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_CONFLICT ||
                           newStatus == PullRequestPublicationStatus.FAILED_PR_VERIFICATION ||
                           newStatus == PullRequestPublicationStatus.FAILED_STALE);
                break;
            case FAILED_PR_CREATION:
                isLegal = (newStatus == PullRequestPublicationStatus.PR_RECONCILED);
                break;
            default:
                isLegal = false;
                break;
        }

        if (!isLegal) {
            throw new IllegalStateException("Illegal state machine transition: " + currentStatus + " -> " + newStatus);
        }
    }

    private void savePublicationPrState(
            PullRequestPublication publication,
            PullRequestPublicationStatus status,
            String failureCategory,
            String failureReason,
            String fenceToken
    ) {
        savePublicationPrState(publication, status, failureCategory, failureReason, null, null, fenceToken);
    }

    private void savePublicationPrState(
            PullRequestPublication publication,
            PullRequestPublicationStatus status,
            String failureCategory,
            String failureReason,
            Long githubPrNumber,
            String prUrl,
            String fenceToken
    ) {
        if (!durableOrchestratorOwnershipService.verifyOwnership(publication.getApproval().getId(), fenceToken)) {
            logger.error("Stale worker lost durable ownership lock for approval {} during savePublicationPrState. Aborting DB write.", publication.getApproval().getId());
            throw new IllegalStateException("Durable fencing ownership lost to another worker during state transition");
        }

        validateStateTransition(publication.getPublicationStatus(), status);

        publication.setPublicationStatus(status);
        publication.setFailureCategory(failureCategory);
        publication.setFailureReason(failureReason);
        if (githubPrNumber != null) {
            publication.setGithubPrNumber(githubPrNumber);
        }
        if (prUrl != null) {
            publication.setPrUrl(prUrl);
        }

        pullRequestPublicationRepository.save(publication);
    }

    private void saveOrUpdatePullRequestEntity(
            Task task,
            Repository repository,
            Long githubPrId,
            String branchName,
            String baseBranch,
            String commitSha,
            String title,
            String description,
            String url
    ) {
        Optional<PullRequest> existing = pullRequestRepository.findByRepositoryIdAndGithubPrId(repository.getId(), githubPrId);
        PullRequest pr = existing.orElseGet(() -> {
            PullRequest newPr = new PullRequest();
            newPr.setTask(task);
            newPr.setRepository(repository);
            newPr.setGithubPrId(githubPrId);
            newPr.setBranchName(branchName);
            newPr.setBaseBranch(baseBranch);
            return newPr;
        });

        pr.setCommitSha(commitSha);
        pr.setTitle(title);
        pr.setDescription(description);
        pr.setUrl(url);
        pr.setStatus("OPEN");

        pullRequestRepository.save(pr);
    }

    private List<GithubPullRequestDto> filterMatchingPrs(
            List<GithubPullRequestDto> prs,
            String headBranch,
            String baseBranch,
            String repoOwner,
            String repoName,
            String githubRepoId
    ) {
        if (prs == null || prs.isEmpty()) {
            return Collections.emptyList();
        }

        List<GithubPullRequestDto> matches = new ArrayList<>();
        for (GithubPullRequestDto pr : prs) {
            if (pr == null || pr.getHead() == null || pr.getBase() == null) {
                continue;
            }
            String headRef = pr.getHead().getRef();
            String baseRef = pr.getBase().getRef();

            if (headRef != null && headRef.equalsIgnoreCase(headBranch) &&
                baseRef != null && baseRef.equalsIgnoreCase(baseBranch)) {

                GithubPullRequestDto.RepoRef headRepo = pr.getHead().getRepo();
                GithubPullRequestDto.RepoRef baseRepo = pr.getBase().getRepo();

                if (headRepo != null && headRepo.getOwner() != null && headRepo.getOwner().getLogin() != null) {
                    if (!headRepo.getOwner().getLogin().equalsIgnoreCase(repoOwner)) {
                        continue; // Exclude fork
                    }
                }
                if (headRepo != null && headRepo.getName() != null) {
                    if (!headRepo.getName().equalsIgnoreCase(repoName)) {
                        continue; // Exclude wrong repo name
                    }
                }
                if (githubRepoId != null && !githubRepoId.isBlank() && headRepo != null && headRepo.getId() != null) {
                    try {
                        long expectedId = Long.parseLong(githubRepoId.trim());
                        if (!headRepo.getId().equals(expectedId)) {
                            continue; // Exclude wrong repo numeric ID
                        }
                    } catch (NumberFormatException ignored) {}
                }

                if (baseRepo != null && baseRepo.getOwner() != null && baseRepo.getOwner().getLogin() != null) {
                    if (!baseRepo.getOwner().getLogin().equalsIgnoreCase(repoOwner)) {
                        continue;
                    }
                }
                if (baseRepo != null && baseRepo.getName() != null) {
                    if (!baseRepo.getName().equalsIgnoreCase(repoName)) {
                        continue;
                    }
                }

                matches.add(pr);
            }
        }
        return matches;
    }

    private boolean verifyPrIdentity(
            GithubPullRequestDto pr,
            String expectedHeadBranch,
            String expectedHeadSha,
            String expectedBaseBranch,
            String expectedOwner,
            String expectedRepoName,
            String expectedGithubRepoId
    ) {
        if (pr == null) return false;
        if (pr.getNumber() == null || pr.getNumber() <= 0) return false;
        if (pr.getHtmlUrl() == null || pr.getHtmlUrl().isBlank()) return false;

        // Head repository check
        if (pr.getHead() == null || pr.getHead().getRepo() == null) {
            logger.warn("PR identity verification failed: head repo is null/malformed");
            return false;
        }
        GithubPullRequestDto.RepoRef headRepo = pr.getHead().getRepo();
        if (headRepo.getOwner() == null || headRepo.getOwner().getLogin() == null ||
            !headRepo.getOwner().getLogin().equalsIgnoreCase(expectedOwner)) {
            logger.warn("PR identity verification failed: head repo owner mismatch");
            return false;
        }
        if (headRepo.getName() == null || !headRepo.getName().equalsIgnoreCase(expectedRepoName)) {
            logger.warn("PR identity verification failed: head repo name mismatch");
            return false;
        }
        if (expectedGithubRepoId != null && !expectedGithubRepoId.isBlank() && headRepo.getId() != null) {
            try {
                long expectedId = Long.parseLong(expectedGithubRepoId.trim());
                if (!headRepo.getId().equals(expectedId)) {
                    logger.warn("PR identity verification failed: head repo numeric ID mismatch. Expected {}, got {}", expectedId, headRepo.getId());
                    return false;
                }
            } catch (NumberFormatException ignored) {}
        }

        // Head ref and SHA check
        if (pr.getHead().getRef() == null || !pr.getHead().getRef().equalsIgnoreCase(expectedHeadBranch)) {
            logger.warn("PR identity verification failed: head branch mismatch");
            return false;
        }
        if (pr.getHead().getSha() == null || !pr.getHead().getSha().equalsIgnoreCase(expectedHeadSha)) {
            logger.warn("PR identity verification failed: head SHA mismatch");
            return false;
        }

        // Base repository check
        if (pr.getBase() == null || pr.getBase().getRepo() == null) {
            logger.warn("PR identity verification failed: base repo is null/malformed");
            return false;
        }
        GithubPullRequestDto.RepoRef baseRepo = pr.getBase().getRepo();
        if (baseRepo.getOwner() == null || baseRepo.getOwner().getLogin() == null ||
            !baseRepo.getOwner().getLogin().equalsIgnoreCase(expectedOwner)) {
            logger.warn("PR identity verification failed: base repo owner mismatch");
            return false;
        }
        if (baseRepo.getName() == null || !baseRepo.getName().equalsIgnoreCase(expectedRepoName)) {
            logger.warn("PR identity verification failed: base repo name mismatch");
            return false;
        }
        if (expectedGithubRepoId != null && !expectedGithubRepoId.isBlank() && baseRepo.getId() != null) {
            try {
                long expectedId = Long.parseLong(expectedGithubRepoId.trim());
                if (!baseRepo.getId().equals(expectedId)) {
                    logger.warn("PR identity verification failed: base repo numeric ID mismatch. Expected {}, got {}", expectedId, baseRepo.getId());
                    return false;
                }
            } catch (NumberFormatException ignored) {}
        }

        // Base ref check
        if (pr.getBase().getRef() == null || !pr.getBase().getRef().equalsIgnoreCase(expectedBaseBranch)) {
            logger.warn("PR identity verification failed: base branch mismatch");
            return false;
        }

        // URL verification
        String lowerUrl = pr.getHtmlUrl().toLowerCase();
        if (!lowerUrl.contains("github.com") ||
            !lowerUrl.contains("/" + expectedOwner.toLowerCase() + "/" + expectedRepoName.toLowerCase() + "/pull/")) {
            logger.warn("PR identity verification failed: invalid PR htmlUrl pattern");
            return false;
        }

        return true;
    }

    private String sanitizeTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            rawTitle = "RepoPilot Implementation";
        }
        String clean = rawTitle.replaceAll("<[^>]*>", "").replaceAll("[\\r\\n]+", " ").trim();
        String formatted = "[RepoPilot] Task: " + clean;
        if (formatted.length() > 256) {
            formatted = formatted.substring(0, 256);
        }
        return SecretRedactor.sanitizeLogs(formatted);
    }

    private String sanitizeBody(String rawDescription, String branchName, String commitSha, char[] tokenChars) {
        String cleanDesc = rawDescription != null ? rawDescription.replaceAll("<script[^>]*>.*?</script>", "").replaceAll("<[^>]*>", "").trim() : "Proposed implementation";
        if (cleanDesc.length() > 3000) {
            cleanDesc = cleanDesc.substring(0, 3000);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## RepoPilot Implementation Summary\n\n");
        sb.append("- **Branch**: `").append(branchName).append("`\n");
        sb.append("- **Commit SHA**: `").append(commitSha).append("`\n\n");
        sb.append("### Description\n");
        sb.append(cleanDesc).append("\n");

        String bodyStr = sb.toString();
        if (bodyStr.length() > 4096) {
            bodyStr = bodyStr.substring(0, 4096);
        }

        if (tokenChars != null && tokenChars.length > 0) {
            bodyStr = SecretRedactor.redact(bodyStr, tokenChars);
        } else {
            bodyStr = SecretRedactor.sanitizeLogs(bodyStr);
        }
        return bodyStr;
    }

    private void logAuditAction(String action, User requester, Project project, Task task, UUID approvalId, String branchName, String detail) {
        auditLogService.logAction(
                requester, project, task,
                action, "HUMAN_APPROVAL", approvalId,
                "{\"branch\":\"" + branchName + "\",\"detail\":\"" + detail + "\"}"
        );
    }
}
