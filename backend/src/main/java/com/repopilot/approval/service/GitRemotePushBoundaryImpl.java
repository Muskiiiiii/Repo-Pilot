package com.repopilot.approval.service;

import com.repopilot.approval.dto.*;
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
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.service.DurableOrchestratorOwnershipService;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class GitRemotePushBoundaryImpl implements GitRemotePushBoundary {

    private static final Logger logger = LoggerFactory.getLogger(GitRemotePushBoundaryImpl.class);
    private static final Pattern BRANCH_NAME_PATTERN = Pattern.compile("^repopilot/task-[a-f0-9]{8}-[a-f0-9]{8}$");

    private final ProjectAuthorizationService projectAuthorizationService;
    private final HumanApprovalRepository humanApprovalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final PullRequestPublicationRepository pullRequestPublicationRepository;
    private final ApprovedChangeVerifier approvedChangeVerifier;
    private final MutationIntegrityVerifier mutationIntegrityVerifier;
    private final GitBranchCommitBoundary gitBranchCommitBoundary;
    private final GithubRemoteIdentityVerifier githubRemoteIdentityVerifier;
    private final GithubCredentialProvider githubCredentialProvider;
    private final GitAskpassManager gitAskpassManager;
    private final GithubApiService githubApiService;
    private final DurableOrchestratorOwnershipService durableOrchestratorOwnershipService;
    private final MutationWorkspaceManager mutationWorkspaceManager;
    private final AuditLogService auditLogService;

    @Value("${repopilot.git.executable-path:git}")
    private String gitExecutablePath = "git";

    public GitRemotePushBoundaryImpl(
            ProjectAuthorizationService projectAuthorizationService,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            PullRequestPublicationRepository pullRequestPublicationRepository,
            ApprovedChangeVerifier approvedChangeVerifier,
            MutationIntegrityVerifier mutationIntegrityVerifier,
            GitBranchCommitBoundary gitBranchCommitBoundary,
            GithubRemoteIdentityVerifier githubRemoteIdentityVerifier,
            GithubCredentialProvider githubCredentialProvider,
            GitAskpassManager gitAskpassManager,
            GithubApiService githubApiService,
            DurableOrchestratorOwnershipService durableOrchestratorOwnershipService,
            MutationWorkspaceManager mutationWorkspaceManager,
            AuditLogService auditLogService
    ) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.pullRequestPublicationRepository = pullRequestPublicationRepository;
        this.approvedChangeVerifier = approvedChangeVerifier;
        this.mutationIntegrityVerifier = mutationIntegrityVerifier;
        this.gitBranchCommitBoundary = gitBranchCommitBoundary;
        this.githubRemoteIdentityVerifier = githubRemoteIdentityVerifier;
        this.githubCredentialProvider = githubCredentialProvider;
        this.gitAskpassManager = gitAskpassManager;
        this.githubApiService = githubApiService;
        this.durableOrchestratorOwnershipService = durableOrchestratorOwnershipService;
        this.mutationWorkspaceManager = mutationWorkspaceManager;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional
    public GitPushResult pushApprovedCommitToRemote(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    ) {
        if (approvalId == null || operationId == null || projectId == null || repositoryId == null || taskId == null || codeChangeId == null) {
            return GitPushResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "INVALID_PARAMETERS", "All operation and entity IDs are required"
            );
        }

        // 1. Authenticated User & Role Authorization Check
        if (requester == null) {
            return GitPushResult.failure(
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
            return GitPushResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_VERIFICATION, "AUTHORIZATION_FAILURE", e.getMessage()
            );
        }

        // 2. Durable Fencing Ownership Acquisition (Redis)
        String fenceToken = durableOrchestratorOwnershipService.acquireOwnership(approvalId, Duration.ofSeconds(60));
        if (fenceToken == null) {
            logger.warn("Durable publication lock acquisition failed for approval {} (already owned or lock service down)", approvalId);
            return GitPushResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PUSH, "PUBLICATION_IN_PROGRESS", "Publication operation is already owned by another worker or lock service unavailable"
            );
        }

        durableOrchestratorOwnershipService.startHeartbeat(approvalId, fenceToken, Duration.ofSeconds(15), Duration.ofSeconds(60));

        try {
            // 3. Pre-Push Approval Re-Verification (Phase 12.2-A)
            ApprovedChangeVerificationResult approvalVerification = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repositoryId, taskId, codeChangeId, approvalId, requester
            );
            if (!approvalVerification.isValid()) {
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_STALE, "APPROVAL_STALE", "Pre-push approval re-verification failed: " + approvalVerification.failureReason()
                );
            }

            // 4. Pre-Push Mutation Integrity Verification (Phase 12.2-D)
            MutationIntegrityResult integrityResult = mutationIntegrityVerifier.verifyMutationIntegrity(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            if (!integrityResult.isVerified()) {
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_VERIFICATION, "WORKSPACE_INTEGRITY_FAILED", "Mutation workspace integrity check failed: " + integrityResult.failureReason()
                );
            }

            // 5. Consume Local Git Branch & Commit Boundary Result (Phase 12.2-E)
            GitMutationResult mutationResult = gitBranchCommitBoundary.createBranchAndCommit(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            if (!mutationResult.isCommitted()) {
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_VERIFICATION, "GIT_COMMIT_FAILED", "Local Git branch/commit creation failed: " + mutationResult.failureReason()
                );
            }

            String branchName = mutationResult.branchName();
            String commitSha = mutationResult.commitSha();
            String parentCommitSha = mutationResult.parentCommitSha();
            String treeSha = mutationResult.treeSha();
            List<String> committedPaths = mutationResult.committedPaths();

            if (!BRANCH_NAME_PATTERN.matcher(branchName).matches()) {
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_VERIFICATION, "INVALID_BRANCH_NAME", "Generated branch name violates format pattern: " + branchName
                );
            }

            HumanApproval approval = humanApprovalRepository.findById(approvalId).orElse(null);
            CodeChange codeChange = codeChangeRepository.findById(codeChangeId).orElse(null);
            Repository repository = repositoryRepository.findById(repositoryId).orElse(null);
            Task task = taskRepository.findById(taskId).orElse(null);

            if (approval == null || codeChange == null || repository == null || task == null) {
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_STALE, "ENTITY_NOT_FOUND", "One or more required entities not found in database"
                );
            }

            if (!parentCommitSha.equalsIgnoreCase(approval.getRepositoryBaseSha())) {
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_VERIFICATION, "PARENT_SHA_MISMATCH", "Parent commit SHA mismatch against approved base SHA"
                );
            }

            // 6. Durable DB State Check & Idempotency
            Optional<PullRequestPublication> existingPubOpt = pullRequestPublicationRepository.findByApprovalId(approvalId);
            if (existingPubOpt.isPresent()) {
                PullRequestPublication existingPub = existingPubOpt.get();
                PullRequestPublicationStatus status = existingPub.getPublicationStatus();
                if (status == PullRequestPublicationStatus.PUSHED || status == PullRequestPublicationStatus.RECONCILED) {
                    if (existingPub.getCommitSha().equalsIgnoreCase(commitSha)) {
                        logger.info("Publication for approval {} is already pushed at commit {} (idempotent skip)", approvalId, commitSha);
                        return GitPushResult.success(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                branchName, existingPub.getBaseBranch(), commitSha, parentCommitSha, treeSha,
                                existingPub.getGithubRepoId(), committedPaths, status
                        );
                    } else {
                        return GitPushResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                PullRequestPublicationStatus.FAILED_CONFLICT, "COMMIT_MISMATCH", "Existing publication record commit SHA mismatch"
                        );
                    }
                } else if (status != PullRequestPublicationStatus.FAILED_PUSH) {
                    // Terminal failure states (other than FAILED_PUSH which may undergo recovery reconciliation) cannot transition
                    return GitPushResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            status,
                            existingPub.getFailureCategory() != null ? existingPub.getFailureCategory() : "TERMINAL_FAILURE",
                            existingPub.getFailureReason() != null ? existingPub.getFailureReason() : "Publication is in terminal failure state " + status
                    );
                }
            }

            // 7. Phase 12.2-F-A GitHub Remote Identity & Base Branch Verification
            String encryptedToken = requester.getGithubAccessToken();
            GithubRemoteVerificationResult remoteIdentity = githubRemoteIdentityVerifier.verifyGithubRemoteIdentity(
                    projectId, repositoryId, taskId, codeChangeId, approvalId, requester, encryptedToken
            );

            if (!remoteIdentity.isVerified()) {
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_STALE, "REMOTE_IDENTITY_FAILED", "Remote identity verification failed: " + remoteIdentity.failureReason()
                );
            }

            String githubRepoId = remoteIdentity.githubRepoId();
            String ownerName = remoteIdentity.ownerName();
            String repoName = remoteIdentity.repoName();
            String baseBranch = remoteIdentity.defaultBranch();

            // Record VERIFYING status in persistent DB store if not already created/failed
            if (existingPubOpt.isEmpty()) {
                savePublicationState(
                        approval, codeChange, repository, task, project, requester,
                        branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                        PullRequestPublicationStatus.VERIFYING, null, null, fenceToken
                );
            }

            // 8. Pre-Push Remote Target Branch Check via GitHub API
            GithubCredentialContext tempCred = githubCredentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester);
            String rawToken = tempCred.getToken();

            BranchDto existingRemoteBranch = null;
            try {
                existingRemoteBranch = githubApiService.fetchBranchDetails(rawToken, ownerName, repoName, branchName);
            } catch (ApiException e) {
                if (e.getStatus() != HttpStatus.NOT_FOUND) {
                    logger.warn("GitHub API error checking remote branch {}/{}:{}: {}", ownerName, repoName, branchName, e.getMessage());
                    tempCred.close();
                    return GitPushResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_PUSH, "GITHUB_API_ERROR", "Failed to check remote branch status: " + e.getMessage()
                    );
                }
            }

            // Pre-Push Branch State Resolution
            if (existingRemoteBranch != null && existingRemoteBranch.getCommitSha() != null) {
                String remoteSha = existingRemoteBranch.getCommitSha().trim();
                if (remoteSha.equalsIgnoreCase(commitSha)) {
                    // Branch already exists at exact commit -> Reconcile as RECONCILED
                    tempCred.close();
                    savePublicationState(
                            approval, codeChange, repository, task, project, requester,
                            branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                            PullRequestPublicationStatus.RECONCILED, null, null, fenceToken
                    );

                    auditLogService.logAction(
                            requester, project, task,
                            "GITHUB_PUSH_RECONCILED", "HUMAN_APPROVAL", approvalId,
                            "{\"branch\":\"" + branchName + "\",\"commitSha\":\"" + commitSha + "\"}"
                    );

                    logger.info("Successfully reconciled existing remote branch {} at commit {}", branchName, commitSha);
                    return GitPushResult.success(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId, committedPaths, PullRequestPublicationStatus.RECONCILED
                    );
                } else {
                    // Remote branch exists at DIFFERENT SHA -> FAIL CLOSED!
                    tempCred.close();
                    savePublicationState(
                            approval, codeChange, repository, task, project, requester,
                            branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                            PullRequestPublicationStatus.FAILED_CONFLICT, "REMOTE_BRANCH_CONFLICT", "Remote branch already exists with different commit SHA: " + remoteSha, fenceToken
                    );

                    auditLogService.logAction(
                            requester, project, task,
                            "GITHUB_PUSH_FAILED", "HUMAN_APPROVAL", approvalId,
                            "{\"branch\":\"" + branchName + "\",\"reason\":\"REMOTE_BRANCH_CONFLICT\"}"
                    );

                    logger.warn("Remote branch conflict on {}/{}:{}: remoteSha={}, localCommitSha={}", ownerName, repoName, branchName, remoteSha, commitSha);
                    return GitPushResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_CONFLICT, "REMOTE_BRANCH_CONFLICT", "Remote branch already exists on GitHub with a different commit SHA: " + remoteSha
                    );
                }
            }

            // If this is a retry of a FAILED_PUSH publication and branch is absent on remote -> Fail closed without pushing again!
            if (existingPubOpt.isPresent() && existingPubOpt.get().getPublicationStatus() == PullRequestPublicationStatus.FAILED_PUSH) {
                tempCred.close();
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_PUSH, "UNKNOWN_PUSH_STATE", "Remote branch absent on retry; re-push not permitted for failed publication"
                );
            }

            // 9. Re-verify Approval TOCTOU & Fencing Ownership Immediately Before External Mutation
            HumanApproval currentApprovalCheck = humanApprovalRepository.findById(approvalId).orElse(null);
            if (currentApprovalCheck == null || !currentApprovalCheck.getStatus().isApproved()) {
                tempCred.close();
                logger.warn("Approval TOCTOU rejection: approval {} status is no longer APPROVED immediately prior to push", approvalId);
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_STALE, "APPROVAL_REVOKED", "Approval was revoked immediately prior to external push"
                );
            }

            if (!durableOrchestratorOwnershipService.verifyOwnership(approvalId, fenceToken)) {
                tempCred.close();
                logger.error("Lost durable ownership lease for approval {} immediately prior to Git push", approvalId);
                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_PUSH, "FENCING_LOST", "Durable ownership lease was lost prior to external mutation"
                );
            }

            // 10. Execute Single Constrained Git Push
            Path workspaceRepoDir = mutationWorkspaceManager.getMutationWorkspacePath(operationId);

            auditLogService.logAction(
                    requester, project, task,
                    "GITHUB_PUSH_STARTED", "HUMAN_APPROVAL", approvalId,
                    "{\"branch\":\"" + branchName + "\",\"commitSha\":\"" + commitSha + "\"}"
            );

            savePublicationState(
                    approval, codeChange, repository, task, project, requester,
                    branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                    PullRequestPublicationStatus.PUSHING, null, null, fenceToken
            );

            boolean pushSuccess = executeGitPushCommand(workspaceRepoDir, branchName, tempCred);
            tempCred.close(); // Zeroize JIT token immediately after git process finishes

            if (!pushSuccess) {
                // Network timeout or execution error -> Issue GitHub REST query before concluding push failure!
                BranchDto unknownStateBranch = null;
                try (GithubCredentialContext recoveryCred = githubCredentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
                    unknownStateBranch = githubApiService.fetchBranchDetails(recoveryCred.getToken(), ownerName, repoName, branchName);
                } catch (Exception e) {
                    logger.warn("Failed to query GitHub remote state after failed git push execution for branch {}: {}", branchName, e.getMessage());
                }

                if (unknownStateBranch != null && unknownStateBranch.getCommitSha() != null) {
                    String actualSha = unknownStateBranch.getCommitSha().trim();
                    if (actualSha.equalsIgnoreCase(commitSha)) {
                        // Push landed on GitHub despite CLI process timeout/error!
                        savePublicationState(
                                approval, codeChange, repository, task, project, requester,
                                branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                                PullRequestPublicationStatus.RECONCILED, null, null, fenceToken
                        );

                        auditLogService.logAction(
                                requester, project, task,
                                "GITHUB_PUSH_RECONCILED", "HUMAN_APPROVAL", approvalId,
                                "{\"branch\":\"" + branchName + "\",\"commitSha\":\"" + commitSha + "\"}"
                        );

                        return GitPushResult.success(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId, committedPaths, PullRequestPublicationStatus.RECONCILED
                        );
                    } else {
                        savePublicationState(
                                approval, codeChange, repository, task, project, requester,
                                branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                                PullRequestPublicationStatus.FAILED_CONFLICT, "REMOTE_BRANCH_CONFLICT", "Remote branch created with different commit SHA: " + actualSha, fenceToken
                        );

                        return GitPushResult.failure(
                                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                PullRequestPublicationStatus.FAILED_CONFLICT, "REMOTE_BRANCH_CONFLICT", "Remote branch created with different commit SHA: " + actualSha
                        );
                    }
                }

                // If remote branch is definitely absent or unknown state
                savePublicationState(
                        approval, codeChange, repository, task, project, requester,
                        branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                        PullRequestPublicationStatus.FAILED_PUSH, "UNKNOWN_PUSH_STATE", "Git push process execution failed or timed out", fenceToken
                );

                auditLogService.logAction(
                        requester, project, task,
                        "GITHUB_PUSH_FAILED", "HUMAN_APPROVAL", approvalId,
                        "{\"branch\":\"" + branchName + "\",\"reason\":\"UNKNOWN_PUSH_STATE\"}"
                );

                return GitPushResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        PullRequestPublicationStatus.FAILED_PUSH, "UNKNOWN_PUSH_STATE", "Git push process execution failed or timed out"
                );
            }

            // 11. Post-Push Independent Remote Ref Verification via GitHub API
            try (GithubCredentialContext postPushCred = githubCredentialProvider.getGithubCredential(projectId, repositoryId, taskId, approvalId, requester)) {
                BranchDto postPushBranch = githubApiService.fetchBranchDetails(postPushCred.getToken(), ownerName, repoName, branchName);
                if (postPushBranch == null || postPushBranch.getCommitSha() == null || !postPushBranch.getCommitSha().trim().equalsIgnoreCase(commitSha)) {
                    String actualSha = postPushBranch != null ? postPushBranch.getCommitSha() : "null";
                    savePublicationState(
                            approval, codeChange, repository, task, project, requester,
                            branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                            PullRequestPublicationStatus.FAILED_VERIFICATION, "POST_PUSH_SHA_MISMATCH", "Post-push remote ref verification failed: remoteSha=" + actualSha + ", expectedSha=" + commitSha, fenceToken
                    );

                    auditLogService.logAction(
                            requester, project, task,
                            "GITHUB_PUSH_FAILED", "HUMAN_APPROVAL", approvalId,
                            "{\"branch\":\"" + branchName + "\",\"reason\":\"POST_PUSH_SHA_MISMATCH\"}"
                    );

                    return GitPushResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            PullRequestPublicationStatus.FAILED_VERIFICATION, "POST_PUSH_SHA_MISMATCH", "Post-push remote SHA mismatch: remote=" + actualSha + ", local=" + commitSha
                    );
                }
            }

            // 12. Save Success State & Emit Audit Signature
            savePublicationState(
                    approval, codeChange, repository, task, project, requester,
                    branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId,
                    PullRequestPublicationStatus.PUSHED, null, null, fenceToken
            );

            auditLogService.logAction(
                    requester, project, task,
                    "GITHUB_PUSH_SUCCEEDED", "HUMAN_APPROVAL", approvalId,
                    "{\"branch\":\"" + branchName + "\",\"commitSha\":\"" + commitSha + "\",\"githubRepoId\":\"" + githubRepoId + "\"}"
            );

            logger.info("Successfully pushed and verified remote branch {} at commit {} for approval {}", branchName, commitSha, approvalId);

            return GitPushResult.success(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    branchName, baseBranch, commitSha, parentCommitSha, treeSha, githubRepoId, committedPaths, PullRequestPublicationStatus.PUSHED
            );

        } catch (IllegalStateException e) {
            logger.error("State transition or fencing error during push operation for approval {}: {}", approvalId, e.getMessage());
            return GitPushResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    PullRequestPublicationStatus.FAILED_PUSH, "FENCING_LOST", e.getMessage()
            );
        } finally {
            if (durableOrchestratorOwnershipService.verifyOwnership(approvalId, fenceToken)) {
                durableOrchestratorOwnershipService.stopHeartbeat(approvalId);
                durableOrchestratorOwnershipService.releaseOwnership(approvalId, fenceToken);
            }
        }
    }

    boolean executeGitPushCommand(Path workspaceRepoDir, String branchName, GithubCredentialContext credentialContext) {
        String refspec = "refs/heads/" + branchName + ":refs/heads/" + branchName;

        List<String> command = new ArrayList<>();
        command.add(gitExecutablePath);
        command.add("-c");
        command.add("core.hooksPath=/dev/null");
        command.add("-c");
        command.add("credential.helper=");
        command.add("-c");
        command.add("include.path=/dev/null");
        command.add("-c");
        command.add("core.autocrlf=false");
        command.add("push");
        command.add("origin");
        command.add(refspec);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceRepoDir.toFile());

        gitAskpassManager.configureProcessBuilder(pb, credentialContext);

        Map<String, String> env = pb.environment();
        env.put("GIT_DIR", workspaceRepoDir.resolve(".git").toAbsolutePath().toString());
        env.put("GIT_WORK_TREE", workspaceRepoDir.toAbsolutePath().toString());
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_SYSTEM", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_COUNT", "0");
        env.put("GIT_SSH_COMMAND", "/bin/false");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("HOME", "/tmp");

        try {
            Process process = pb.start();
            StringBuilder stderr = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(SecretRedactor.sanitizeLogs(line)).append("\n");
                }
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.error("Git push command timed out after 30s: {}", SecretRedactor.sanitizeLogs(command.toString()));
                return false;
            }

            if (process.exitValue() != 0) {
                logger.warn("Git push command failed with exit code {}: {}", process.exitValue(), SecretRedactor.sanitizeLogs(stderr.toString().trim()));
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.error("Failed to execute Git push command: {}", SecretRedactor.sanitizeLogs(e.getMessage()), e);
            return false;
        }
    }

    private PullRequestPublication savePublicationState(
            HumanApproval approval,
            CodeChange codeChange,
            Repository repository,
            Task task,
            Project project,
            User requester,
            String branchName,
            String baseBranch,
            String commitSha,
            String parentCommitSha,
            String treeSha,
            String githubRepoId,
            PullRequestPublicationStatus status,
            String failureCategory,
            String failureReason,
            String fenceToken
    ) {
        // Stale worker guard: verify fence token before attempting DB state update
        if (fenceToken != null && !durableOrchestratorOwnershipService.verifyOwnership(approval.getId(), fenceToken)) {
            logger.error("Stale worker lost durable ownership lock for approval {} during savePublicationState. Aborting DB write.", approval.getId());
            throw new IllegalStateException("Durable fencing ownership lost to another worker during state transition");
        }

        PullRequestPublication pub = pullRequestPublicationRepository.findByApprovalId(approval.getId())
                .orElseGet(() -> {
                    PullRequestPublication p = new PullRequestPublication();
                    p.setApproval(approval);
                    p.setCodeChange(codeChange);
                    p.setRepository(repository);
                    p.setTask(task);
                    p.setProject(project);
                    p.setInitiatedByUser(requester);
                    p.setBranchName(branchName);
                    p.setBaseBranch(baseBranch);
                    p.setCommitSha(commitSha);
                    p.setParentCommitSha(parentCommitSha);
                    p.setTreeSha(treeSha);
                    p.setGithubRepoId(githubRepoId);
                    return p;
                });

        validateStateTransition(pub.getPublicationStatus(), status);

        pub.setPublicationStatus(status);
        pub.setFailureCategory(failureCategory);
        pub.setFailureReason(failureReason);

        return pullRequestPublicationRepository.save(pub);
    }

    void validateStateTransition(PullRequestPublicationStatus currentStatus, PullRequestPublicationStatus newStatus) {
        if (currentStatus == null || currentStatus == newStatus) {
            return;
        }

        // Terminal state immutability rule: terminal states CANNOT transition to non-terminal or different terminal states (except FAILED_PUSH -> RECONCILED for recovery reconciliation)
        if (currentStatus.isTerminal() && !(currentStatus == PullRequestPublicationStatus.FAILED_PUSH && newStatus == PullRequestPublicationStatus.RECONCILED)) {
            throw new IllegalStateException("Illegal state machine transition: terminal state " + currentStatus + " cannot transition to " + newStatus);
        }

        // Explicit Legal Transition Matrix:
        // PENDING -> VERIFYING, FAILED_STALE, FAILED_VERIFICATION, FAILED_PUSH
        // VERIFYING -> PUSHING, RECONCILED, FAILED_STALE, FAILED_CONFLICT, FAILED_VERIFICATION, FAILED_PUSH
        // PUSHING -> PUSHED, RECONCILED, FAILED_PUSH, FAILED_VERIFICATION, FAILED_CONFLICT, FAILED_STALE
        // FAILED_PUSH -> RECONCILED (recovery reconciliation)
        boolean isLegal = false;
        switch (currentStatus) {
            case PENDING:
                isLegal = (newStatus == PullRequestPublicationStatus.VERIFYING ||
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
            case FAILED_PUSH:
                isLegal = (newStatus == PullRequestPublicationStatus.RECONCILED);
                break;
            default:
                isLegal = false;
                break;
        }

        if (!isLegal) {
            throw new IllegalStateException("Illegal state machine transition: " + currentStatus + " -> " + newStatus);
        }
    }
}
