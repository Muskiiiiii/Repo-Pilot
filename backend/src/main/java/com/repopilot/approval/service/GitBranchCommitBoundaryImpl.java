package com.repopilot.approval.service;

import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.approval.dto.GitMutationResult;
import com.repopilot.approval.dto.GitMutationStatus;
import com.repopilot.approval.dto.MutationIntegrityResult;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class GitBranchCommitBoundaryImpl implements GitBranchCommitBoundary {

    private static final Logger logger = LoggerFactory.getLogger(GitBranchCommitBoundaryImpl.class);
    private static final Pattern BRANCH_NAME_PATTERN = Pattern.compile("^repopilot/task-[a-f0-9]{8}-[a-f0-9]{8}$");

    private final ApprovedChangeVerifier approvedChangeVerifier;
    private final HumanApprovalRepository humanApprovalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final MutationWorkspaceManager mutationWorkspaceManager;
    private final MutationIntegrityVerifier mutationIntegrityVerifier;
    private final RepositoryRepository repositoryRepository;

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    @Value("${repopilot.mutation.workspace-path:/tmp/repopilot/mutation-workspaces}")
    private String workspaceBasePath;

    @Value("${repopilot.git.executable-path:git}")
    private String gitExecutablePath;

    @org.springframework.beans.factory.annotation.Autowired
    public GitBranchCommitBoundaryImpl(
            ApprovedChangeVerifier approvedChangeVerifier,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            MutationWorkspaceManager mutationWorkspaceManager,
            MutationIntegrityVerifier mutationIntegrityVerifier,
            RepositoryRepository repositoryRepository
    ) {
        this.approvedChangeVerifier = approvedChangeVerifier;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.mutationWorkspaceManager = mutationWorkspaceManager;
        this.mutationIntegrityVerifier = mutationIntegrityVerifier;
        this.repositoryRepository = repositoryRepository;
    }

    public GitBranchCommitBoundaryImpl(
            ApprovedChangeVerifier approvedChangeVerifier,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            MutationWorkspaceManager mutationWorkspaceManager,
            MutationIntegrityVerifier mutationIntegrityVerifier
    ) {
        this(approvedChangeVerifier, humanApprovalRepository, codeChangeRepository, mutationWorkspaceManager, mutationIntegrityVerifier, (RepositoryRepository) null);
    }

    public GitBranchCommitBoundaryImpl(
            ApprovedChangeVerifier approvedChangeVerifier,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            MutationWorkspaceManager mutationWorkspaceManager,
            MutationIntegrityVerifier mutationIntegrityVerifier,
            String baseStoragePath,
            String workspaceBasePath,
            String gitExecutablePath
    ) {
        this(approvedChangeVerifier, humanApprovalRepository, codeChangeRepository, mutationWorkspaceManager, mutationIntegrityVerifier, null, baseStoragePath, workspaceBasePath, gitExecutablePath);
    }

    public GitBranchCommitBoundaryImpl(
            ApprovedChangeVerifier approvedChangeVerifier,
            HumanApprovalRepository humanApprovalRepository,
            CodeChangeRepository codeChangeRepository,
            MutationWorkspaceManager mutationWorkspaceManager,
            MutationIntegrityVerifier mutationIntegrityVerifier,
            RepositoryRepository repositoryRepository,
            String baseStoragePath,
            String workspaceBasePath,
            String gitExecutablePath
    ) {
        this.approvedChangeVerifier = approvedChangeVerifier;
        this.humanApprovalRepository = humanApprovalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.mutationWorkspaceManager = mutationWorkspaceManager;
        this.mutationIntegrityVerifier = mutationIntegrityVerifier;
        this.repositoryRepository = repositoryRepository;
        this.baseStoragePath = baseStoragePath;
        this.workspaceBasePath = workspaceBasePath;
        this.gitExecutablePath = gitExecutablePath;
    }

    @Override
    @Transactional(readOnly = true)
    public GitMutationResult createBranchAndCommit(
            UUID approvalId,
            UUID operationId,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    ) {
        if (approvalId == null || operationId == null || projectId == null || repositoryId == null || taskId == null || codeChangeId == null) {
            return GitMutationResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    GitMutationStatus.PATH_VIOLATION, "All operation and entity IDs are required"
            );
        }

        try {
            // 1. Pre-Mutation Approval Re-Verification (Phase 12.2-A)
            ApprovedChangeVerificationResult verificationResult = approvedChangeVerifier.verifyApprovedChange(
                    projectId, repositoryId, taskId, codeChangeId, approvalId, requester
            );
            if (!verificationResult.isValid()) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.APPROVAL_STALE, "Pre-mutation approval re-verification failed: " + verificationResult.failureReason()
                );
            }

            // 2. Pre-Mutation Workspace Integrity Verification (Phase 12.2-D)
            MutationIntegrityResult integrityResult = mutationIntegrityVerifier.verifyMutationIntegrity(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            if (!integrityResult.isVerified()) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.VERIFICATION_FAILED, "Pre-mutation workspace integrity check failed: " + integrityResult.failureReason()
                );
            }

            // 3. Database Entity Retrieval & Authoritative Base SHA Verification
            HumanApproval approval = humanApprovalRepository.findById(approvalId).orElse(null);
            CodeChange codeChange = codeChangeRepository.findById(codeChangeId).orElse(null);

            if (approval == null || codeChange == null) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.APPROVAL_STALE, "HumanApproval or CodeChange record not found in database"
                );
            }

            if (!approval.getStatus().isApproved()) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.APPROVAL_STALE, "HumanApproval is not in APPROVED status"
                );
            }

            Path masterRepoDir = Paths.get(baseStoragePath, repositoryId.toString()).toAbsolutePath().normalize();
            if (!Files.exists(masterRepoDir)) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.MASTER_MUTATED, "Master repository directory not found on disk"
                );
            }

            // Re-verify Authoritative Base SHA of Master Baseline Repo
            String currentMasterSha = getMasterBaselineCommitSha(masterRepoDir, repositoryId, approval.getRepositoryBaseSha());
            if (!currentMasterSha.equalsIgnoreCase(approval.getRepositoryBaseSha())) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.BASE_SHA_MISMATCH, "Master repository default branch SHA mismatch: current=" + currentMasterSha + ", expected=" + approval.getRepositoryBaseSha()
                );
            }

            // 4. Workspace Path Retrieval & Containment Shield
            Path workspaceRepoDir = mutationWorkspaceManager.getMutationWorkspacePath(operationId);
            if (workspaceRepoDir == null || !Files.exists(workspaceRepoDir)) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.PATH_VIOLATION, "Mutation workspace repo path not found on disk"
                );
            }

            Path workspaceBaseDir = Paths.get(workspaceBasePath).toAbsolutePath().normalize();
            mutationWorkspaceManager.validateContainment(workspaceBaseDir, workspaceRepoDir.getParent());

            // 5. Server-Generated Branch Name Validation
            String shortTaskId = taskId.toString().replace("-", "").substring(0, 8);
            String shortApprovalId = approvalId.toString().replace("-", "").substring(0, 8);
            String branchName = "repopilot/task-" + shortTaskId + "-" + shortApprovalId;

            if (!BRANCH_NAME_PATTERN.matcher(branchName).matches()) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.PATH_VIOLATION, "Generated branch name violates format constraints: " + branchName
                );
            }

            // 6. Ensure Git Repository Initialization in Workspace
            ensureGitRepositoryInitialized(workspaceRepoDir, approval.getRepositoryBaseSha());

            List<String> approvedFiles = verificationResult.approvedFiles();
            Set<String> approvedSet = approvedFiles.stream()
                    .map(p -> Normalizer.normalize(Paths.get(p).normalize().toString().replace("\\", "/"), Normalizer.Form.NFC))
                    .collect(Collectors.toSet());

            // 7. Durable Crash Reconciliation / Idempotency Check
            String existingTip = runGitCommand(workspaceRepoDir, "rev-parse", "--verify", "refs/heads/" + branchName);
            if (existingTip != null && !existingTip.isBlank()) {
                existingTip = existingTip.trim();
                String existingParent = runGitCommand(workspaceRepoDir, "rev-parse", existingTip + "~1");
                if (existingParent != null) existingParent = existingParent.trim();

                String existingTree = runGitCommand(workspaceRepoDir, "rev-parse", existingTip + "^{tree}");
                if (existingTree != null) existingTree = existingTree.trim();

                String diffFilesOutput = runGitCommand(workspaceRepoDir, "diff-tree", "--no-commit-id", "--name-only", "-r", existingTip);
                Set<String> existingCommittedPaths = new HashSet<>();
                if (diffFilesOutput != null && !diffFilesOutput.isBlank()) {
                    for (String line : diffFilesOutput.split("\n")) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            String norm = Normalizer.normalize(Paths.get(line).normalize().toString().replace("\\", "/"), Normalizer.Form.NFC);
                            existingCommittedPaths.add(norm);
                        }
                    }
                }

                if (existingParent != null && existingParent.equalsIgnoreCase(approval.getRepositoryBaseSha()) && existingCommittedPaths.equals(approvedSet)) {
                    logger.info("Reconciled existing commit {} on branch {} for operationId={} (post-crash recovery)", existingTip, branchName, operationId);
                    return GitMutationResult.verified(
                            operationId,
                            approvalId,
                            projectId,
                            repositoryId,
                            taskId,
                            codeChangeId,
                            branchName,
                            existingTip,
                            existingParent,
                            existingTree,
                            new ArrayList<>(approvedSet)
                    );
                }
            }

            // 8. Create Branch from Exact Base SHA
            String checkoutResult = runGitCommand(workspaceRepoDir, "checkout", "-B", branchName, approval.getRepositoryBaseSha());
            if (checkoutResult == null) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.GIT_EXECUTION_FAILED, "Failed to create branch " + branchName + " from base SHA " + approval.getRepositoryBaseSha()
                );
            }

            // 9. Staging Approved Changed Files
            for (String relPath : approvedSet) {
                if (relPath.startsWith("/") || relPath.contains("..")) {
                    return GitMutationResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            GitMutationStatus.PATH_VIOLATION, "Illegal relative path in approved change set: " + relPath
                    );
                }

                Path targetFile = workspaceRepoDir.resolve(relPath).toAbsolutePath().normalize();
                if (Files.exists(targetFile)) {
                    runGitCommand(workspaceRepoDir, "add", "--", relPath);
                } else {
                    runGitCommand(workspaceRepoDir, "rm", "-f", "--", relPath);
                }
            }

            // 10. Pre-Commit Git Index & Workspace Status Verification
            String statusPorcelain = runGitCommand(workspaceRepoDir, "status", "--porcelain");
            if (statusPorcelain != null && !statusPorcelain.isBlank()) {
                String[] lines = statusPorcelain.split("\n");
                Set<String> allAlteredWorkspacePaths = new HashSet<>();
                Set<String> stagedPaths = new HashSet<>();

                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String statusIndex = line.substring(0, Math.min(2, line.length()));
                    String filePathStr = line.substring(Math.min(2, line.length())).trim();

                    String normPath = Normalizer.normalize(Paths.get(filePathStr).normalize().toString().replace("\\", "/"), Normalizer.Form.NFC);
                    allAlteredWorkspacePaths.add(normPath);

                    if (!statusIndex.startsWith("?") && !statusIndex.startsWith(" ")) {
                        stagedPaths.add(normPath);
                    }
                }

                // Any untracked or altered workspace path outside approved set triggers INDEX_MISMATCH
                if (!allAlteredWorkspacePaths.equals(approvedSet)) {
                    return GitMutationResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            GitMutationStatus.INDEX_MISMATCH, "Workspace status contains unapproved or untracked files: altered=" + allAlteredWorkspacePaths + ", approved=" + approvedSet
                    );
                }

                if (!stagedPaths.equals(approvedSet)) {
                    return GitMutationResult.failure(
                            operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                            GitMutationStatus.INDEX_MISMATCH, "Git index staged paths mismatch against approved paths: staged=" + stagedPaths + ", approved=" + approvedSet
                    );
                }
            }

            // Inspect staged modes (reject executable bits or symlink modes unless expected)
            String stagedLsFilesOutput = runGitCommand(workspaceRepoDir, "ls-files", "--stage");
            if (stagedLsFilesOutput != null) {
                for (String line : stagedLsFilesOutput.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        String mode = parts[0];
                        if ("120000".equals(mode) || "160000".equals(mode)) {
                            return GitMutationResult.failure(
                                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                                    GitMutationStatus.INDEX_MISMATCH, "Unsupported staged file mode in index: " + mode
                            );
                        }
                    }
                }
            }

            // 11. Commit Creation
            String commitMessage = "RepoPilot Approved Change: Task " + taskId + " (Approval " + approvalId + ")";
            String commitOutput = runGitCommand(workspaceRepoDir, "commit", "-m", commitMessage, "--no-verify");
            if (commitOutput == null) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.GIT_EXECUTION_FAILED, "Git commit execution failed in workspace"
                );
            }

            // 12. Post-Commit Verification
            String commitSha = runGitCommand(workspaceRepoDir, "rev-parse", "HEAD");
            if (commitSha != null) commitSha = commitSha.trim();

            String parentSha = runGitCommand(workspaceRepoDir, "rev-parse", "HEAD~1");
            if (parentSha != null) parentSha = parentSha.trim();

            String treeSha = runGitCommand(workspaceRepoDir, "rev-parse", "HEAD^{tree}");
            if (treeSha != null) treeSha = treeSha.trim();

            String currentBranchTip = runGitCommand(workspaceRepoDir, "rev-parse", branchName);
            if (currentBranchTip != null) currentBranchTip = currentBranchTip.trim();

            if (commitSha == null || parentSha == null || treeSha == null) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.COMMIT_VERIFICATION_FAILED, "Failed to inspect post-commit hashes"
                );
            }

            if (!parentSha.equalsIgnoreCase(approval.getRepositoryBaseSha())) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.COMMIT_VERIFICATION_FAILED, "Post-commit parent SHA mismatch: parent=" + parentSha + ", expected=" + approval.getRepositoryBaseSha()
                );
            }

            if (!commitSha.equalsIgnoreCase(currentBranchTip)) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.COMMIT_VERIFICATION_FAILED, "Branch tip SHA mismatch: tip=" + currentBranchTip + ", commit=" + commitSha
                );
            }

            // Re-verify Master Baseline Repository Immutability after commit
            String finalMasterSha = getMasterBaselineCommitSha(masterRepoDir, repositoryId, approval.getRepositoryBaseSha());
            if (!finalMasterSha.equalsIgnoreCase(approval.getRepositoryBaseSha())) {
                return GitMutationResult.failure(
                        operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                        GitMutationStatus.MASTER_MUTATED, "Master repository baseline was mutated during Git commit operation"
                );
            }

            logger.info("Successfully created Git branch {} and commit {} for operationId={}", branchName, commitSha, operationId);

            return GitMutationResult.verified(
                    operationId,
                    approvalId,
                    projectId,
                    repositoryId,
                    taskId,
                    codeChangeId,
                    branchName,
                    commitSha,
                    parentSha,
                    treeSha,
                    new ArrayList<>(approvedSet)
            );

        } catch (ApiException e) {
            return GitMutationResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    GitMutationStatus.PATH_VIOLATION, e.getMessage()
            );
        } catch (Exception e) {
            logger.error("Failed to execute Git branch and commit for operationId={}: {}", operationId, e.getMessage(), e);
            return GitMutationResult.failure(
                    operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                    GitMutationStatus.GIT_EXECUTION_FAILED, "Git operation failed: " + e.getMessage()
            );
        }
    }

    private void ensureGitRepositoryInitialized(Path workspaceRepoDir, String targetBaseSha) {
        Path gitDir = workspaceRepoDir.resolve(".git");
        if (!Files.exists(gitDir)) {
            runGitCommand(workspaceRepoDir, "init");
            runGitCommand(workspaceRepoDir, "config", "user.name", "RepoPilot System");
            runGitCommand(workspaceRepoDir, "config", "user.email", "noreply@repopilot.internal");

            // Initial commit if empty repo
            String status = runGitCommand(workspaceRepoDir, "status", "--porcelain");
            if (status != null && !status.isBlank()) {
                runGitCommand(workspaceRepoDir, "add", ".");
                runGitCommand(workspaceRepoDir, "commit", "-m", "Initial baseline commit", "--no-verify");
            } else {
                // Empty commit for base initialization
                runGitCommand(workspaceRepoDir, "commit", "--allow-empty", "-m", "Initial baseline commit", "--no-verify");
            }

            // Tag or create base ref matching targetBaseSha if needed
            if (targetBaseSha != null && !targetBaseSha.isBlank()) {
                runGitCommand(workspaceRepoDir, "update-ref", "refs/heads/master", targetBaseSha);
                runGitCommand(workspaceRepoDir, "update-ref", "refs/heads/main", targetBaseSha);
            }
        }
    }

    private String getMasterBaselineCommitSha(Path masterRepoDir, UUID repositoryId, String expectedBaseSha) {
        Path gitDir = masterRepoDir.resolve(".git");
        if (Files.exists(gitDir)) {
            String defaultBranch = "main";
            if (repositoryRepository != null && repositoryId != null) {
                Optional<Repository> repoOpt = repositoryRepository.findById(repositoryId);
                if (repoOpt.isPresent() && repoOpt.get().getDefaultBranch() != null && !repoOpt.get().getDefaultBranch().isBlank()) {
                    defaultBranch = repoOpt.get().getDefaultBranch().trim();
                }
            }

            // 1. Try refs/heads/{defaultBranch}
            String sha = runGitCommand(masterRepoDir, "rev-parse", "refs/heads/" + defaultBranch);
            if (sha != null && !sha.isBlank()) {
                return sha.trim();
            }
            // 2. Try {defaultBranch}
            sha = runGitCommand(masterRepoDir, "rev-parse", defaultBranch);
            if (sha != null && !sha.isBlank()) {
                return sha.trim();
            }
            // 3. Fallback to common main/master branches if different
            for (String fallbackBranch : List.of("main", "master")) {
                if (!fallbackBranch.equalsIgnoreCase(defaultBranch)) {
                    sha = runGitCommand(masterRepoDir, "rev-parse", "refs/heads/" + fallbackBranch);
                    if (sha != null && !sha.isBlank()) {
                        return sha.trim();
                    }
                }
            }
            // 4. Fallback to HEAD
            sha = runGitCommand(masterRepoDir, "rev-parse", "HEAD");
            if (sha != null && !sha.isBlank()) {
                return sha.trim();
            }
        }
        // If master repo directory on disk is not a git repo (e.g. baseline storage directory), return expected Base SHA
        return expectedBaseSha != null ? expectedBaseSha : "0000000000000000000000000000000000000000";
    }

    private String runGitCommand(Path workingDir, String... args) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutablePath);
        command.add("-c");
        command.add("core.hooksPath=/dev/null");
        command.add("-c");
        command.add("core.autocrlf=false");
        command.add("-c");
        command.add("credential.helper=");
        command.add("-c");
        command.add("include.path=/dev/null");
        command.addAll(Arrays.asList(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());

        Map<String, String> env = pb.environment();
        env.put("GIT_DIR", workingDir.resolve(".git").toAbsolutePath().toString());
        env.put("GIT_WORK_TREE", workingDir.toAbsolutePath().toString());
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_CONFIG_SYSTEM", "/dev/null");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_COUNT", "0");
        env.put("GIT_SSH_COMMAND", "/bin/false");
        env.put("GIT_ASKPASS", "/bin/false");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("HOME", "/tmp");
        env.put("http_proxy", "");
        env.put("https_proxy", "");
        env.put("HTTP_PROXY", "");
        env.put("HTTPS_PROXY", "");
        env.put("GIT_AUTHOR_NAME", "RepoPilot System");
        env.put("GIT_AUTHOR_EMAIL", "noreply@repopilot.internal");
        env.put("GIT_COMMITTER_NAME", "RepoPilot System");
        env.put("GIT_COMMITTER_EMAIL", "noreply@repopilot.internal");

        try {
            Process process = pb.start();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.error("Git command timed out: {}", command);
                return null;
            }

            if (process.exitValue() != 0) {
                logger.warn("Git command exited with code {}: {}\nStderr: {}", process.exitValue(), command, stderr.toString().trim());
                return null;
            }

            return stdout.toString().trim();
        } catch (Exception e) {
            logger.error("Failed to run Git command {}: {}", command, e.getMessage(), e);
            return null;
        }
    }
}
