package com.repopilot.approval;

import com.repopilot.approval.dto.*;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.service.*;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.repopilot.repository.repository.RepositoryRepository;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase12_2EGitBranchCommitBoundaryTest {

    @TempDir
    Path tempStorageDir;

    @TempDir
    Path tempWorkspacesDir;

    private ApprovedChangeVerifier approvedChangeVerifier;
    private HumanApprovalRepository humanApprovalRepository;
    private CodeChangeRepository codeChangeRepository;
    private MutationWorkspaceManager mutationWorkspaceManager;
    private MutationIntegrityVerifier mutationIntegrityVerifier;
    private RepositoryRepository repositoryRepository;
    private GitBranchCommitBoundary boundary;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID codeChangeId;
    private UUID approvalId;
    private UUID operationId;
    private User requester;

    private Path masterRepoDir;
    private Path workspaceDir;
    private Path workspaceRepoDir;

    private CodeChange codeChange;
    private HumanApproval approval;
    private Project project;
    private Repository repository;
    private Task task;
    private CodeReview review;
    private String baseSha;

    @BeforeEach
    void setUp() throws IOException {
        approvedChangeVerifier = mock(ApprovedChangeVerifier.class);
        humanApprovalRepository = mock(HumanApprovalRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        mutationWorkspaceManager = mock(MutationWorkspaceManager.class);
        mutationIntegrityVerifier = mock(MutationIntegrityVerifier.class);
        repositoryRepository = mock(RepositoryRepository.class);

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();
        approvalId = UUID.randomUUID();
        operationId = UUID.randomUUID();

        requester = new User();
        requester.setId(UUID.randomUUID());

        boundary = new GitBranchCommitBoundaryImpl(
                approvedChangeVerifier,
                humanApprovalRepository,
                codeChangeRepository,
                mutationWorkspaceManager,
                mutationIntegrityVerifier,
                repositoryRepository,
                tempStorageDir.toAbsolutePath().toString(),
                tempWorkspacesDir.toAbsolutePath().toString(),
                "git"
        );

        masterRepoDir = tempStorageDir.resolve(repositoryId.toString());
        Files.createDirectories(masterRepoDir);

        workspaceDir = tempWorkspacesDir.resolve("mutation-" + approvalId + "-" + operationId);
        workspaceRepoDir = workspaceDir.resolve("repo");
        Files.createDirectories(workspaceRepoDir);

        // Initialize a real Git repository in masterRepoDir
        runGit(masterRepoDir, "init");
        runGit(masterRepoDir, "config", "user.name", "Test User");
        runGit(masterRepoDir, "config", "user.email", "test@example.com");
        Files.writeString(masterRepoDir.resolve("App.java"), "public class App {\n  // Original\n}\n");
        runGit(masterRepoDir, "add", ".");
        runGit(masterRepoDir, "commit", "-m", "Initial baseline commit");

        baseSha = runGit(masterRepoDir, "rev-parse", "HEAD").trim();

        // Initialize workspaceRepoDir by copying masterRepoDir (including .git) to simulate Phase 12.2-B
        copyDirectoryRecursively(masterRepoDir, workspaceRepoDir);

        // Workspace file after Phase 12.2-C materialization
        Files.writeString(workspaceRepoDir.resolve("App.java"), "public class App {\n  // Approved Patch\n}\n");

        String originalHash = hashString("public class App {\n  // Original\n}\n");
        String proposedContent = "public class App {\n  // Approved Patch\n}\n";
        String proposedHash = hashString(proposedContent);
        String diffContent = "@@ -1,3 +1,3 @@\n public class App {\n-  // Original\n+  // Approved Patch\n }\n";
        String diffHash = hashString(diffContent);

        codeChange = new CodeChange();
        codeChange.setId(codeChangeId);
        codeChange.setFilePath("App.java");
        codeChange.setOperation("MODIFY");
        codeChange.setOldContent("public class App {\n  // Original\n}\n");
        codeChange.setNewContent(proposedContent);
        codeChange.setOriginalHash(originalHash);
        codeChange.setProposedHash(proposedHash);
        codeChange.setDiff(diffContent);

        project = new Project();
        project.setId(projectId);

        repository = new Repository();
        repository.setId(repositoryId);
        repository.setDefaultBranch("main");

        task = new Task();
        task.setId(taskId);

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
        approval.setCodeChangeOriginalHash(originalHash);
        approval.setCodeChangeProposedHash(proposedHash);
        approval.setCodeChangeDiffHash(diffHash);
        approval.setApprovedFiles("App.java");
        approval.setApprovalRecordHash("valid_record_hash");
        approval.setStatus(ApprovalStatus.APPROVED);

        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(codeChangeRepository.findById(codeChangeId)).thenReturn(Optional.of(codeChange));
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(mutationWorkspaceManager.getMutationWorkspacePath(operationId)).thenReturn(workspaceRepoDir);

        ApprovedChangeVerificationResult validVerification = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, review.getId(),
                1, baseSha, originalHash, proposedHash, diffHash, "review_hash",
                List.of("App.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validVerification);

        MutationIntegrityResult verifiedIntegrity = MutationIntegrityResult.verified(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                "master_digest", "workspace_digest", List.of("App.java")
        );
        when(mutationIntegrityVerifier.verifyMutationIntegrity(any(), any(), any(), any(), any(), any(), any())).thenReturn(verifiedIntegrity);
    }

    @Test
    @DisplayName("1. Valid Branch & Commit: Creates server-generated branch and commit cleanly -> COMMITTED")
    void testCreateBranchAndCommit_Success() {
        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isCommitted());
        assertEquals(GitMutationStatus.COMMITTED, result.status());
        assertNotNull(result.branchName());
        assertTrue(result.branchName().startsWith("repopilot/task-"));
        assertNotNull(result.commitSha());
        assertNotNull(result.parentCommitSha());
        assertEquals(baseSha, result.parentCommitSha());
        assertEquals(1, result.committedPaths().size());
        assertTrue(result.committedPaths().contains("App.java"));
    }

    @Test
    @DisplayName("2. Base SHA Mismatch: Fails closed when master base SHA does not match approval")
    void testCreateBranchAndCommit_BaseShaMismatch() {
        HumanApproval badShaApproval = new HumanApproval();
        badShaApproval.setId(approvalId);
        badShaApproval.setProject(project);
        badShaApproval.setRepository(repository);
        badShaApproval.setTask(task);
        badShaApproval.setCodeChange(codeChange);
        badShaApproval.setReview(review);
        badShaApproval.setRepositoryBaseSha("1111222233334444555566667777888899990000");
        badShaApproval.setCodeChangeOriginalHash(approval.getCodeChangeOriginalHash());
        badShaApproval.setCodeChangeProposedHash(approval.getCodeChangeProposedHash());
        badShaApproval.setCodeChangeDiffHash(approval.getCodeChangeDiffHash());
        badShaApproval.setApprovedFiles("App.java");
        badShaApproval.setApprovalRecordHash("valid_record_hash");
        badShaApproval.setStatus(ApprovalStatus.APPROVED);

        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(badShaApproval));

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCommitted());
        assertEquals(GitMutationStatus.BASE_SHA_MISMATCH, result.status());
        assertNotNull(result.failureReason());
    }

    @Test
    @DisplayName("3. Approval Re-Verification Failure: Fails closed when approval is stale or invalid")
    void testCreateBranchAndCommit_StaleApproval() {
        ApprovedChangeVerificationResult invalidVerification = ApprovedChangeVerificationResult.failure(
                com.repopilot.approval.dto.VerificationFailureCategory.INVALID_STATUS, "Approval status is REJECTED",
                approvalId, projectId, repositoryId, taskId, codeChangeId
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(invalidVerification);

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCommitted());
        assertEquals(GitMutationStatus.APPROVAL_STALE, result.status());
    }

    @Test
    @DisplayName("4. Pre-Mutation Integrity Check Failure: Fails closed when Phase 12.2-D fails")
    void testCreateBranchAndCommit_IntegrityVerificationFailed() {
        MutationIntegrityResult failedIntegrity = MutationIntegrityResult.failure(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                MutationIntegrityStatus.UNEXPECTED_FILE, "Unapproved file found in workspace"
        );
        when(mutationIntegrityVerifier.verifyMutationIntegrity(any(), any(), any(), any(), any(), any(), any())).thenReturn(failedIntegrity);

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCommitted());
        assertEquals(GitMutationStatus.VERIFICATION_FAILED, result.status());
    }

    @Test
    @DisplayName("5. Malicious Pre-Commit Hook Neutralized: Core hooks disabled via core.hooksPath=/dev/null")
    void testCreateBranchAndCommit_MaliciousHookNeutralized() throws IOException {
        Path hooksDir = workspaceRepoDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path preCommit = hooksDir.resolve("pre-commit");
        Files.writeString(preCommit, "#!/bin/sh\nexit 1\n");
        preCommit.toFile().setExecutable(true);

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isCommitted());
        assertEquals(GitMutationStatus.COMMITTED, result.status());
    }

    @Test
    @DisplayName("6. Master Repository Immutability: Master default branch remains untouched")
    void testCreateBranchAndCommit_MasterBaselineUntouched() {
        String masterShaBefore = runGit(masterRepoDir, "rev-parse", "HEAD").trim();

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertTrue(result.isCommitted());

        String masterShaAfter = runGit(masterRepoDir, "rev-parse", "HEAD").trim();
        assertEquals(masterShaBefore, masterShaAfter);
    }

    @Test
    @DisplayName("7. Staged Path Mismatch Detection: Rejects commit if unapproved extra file is staged")
    void testCreateBranchAndCommit_UnapprovedFileInStaging() throws IOException {
        Files.writeString(workspaceRepoDir.resolve("Unapproved.java"), "class Unapproved {}");

        MutationIntegrityResult IntegrityWithExtra = MutationIntegrityResult.verified(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                "master_digest", "workspace_digest", List.of("App.java", "Unapproved.java")
        );
        when(mutationIntegrityVerifier.verifyMutationIntegrity(any(), any(), any(), any(), any(), any(), any())).thenReturn(IntegrityWithExtra);

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCommitted());
        assertEquals(GitMutationStatus.INDEX_MISMATCH, result.status());
    }

    @Test
    @DisplayName("8. Master Branch Advanced During Mutation: Returns MASTER_MUTATED if master moves before verification completes")
    void testCreateBranchAndCommit_MasterBranchAdvanced() throws IOException {
        // Mutate master repo commit after baseSha recorded
        Files.writeString(masterRepoDir.resolve("MasterMutated.java"), "class MasterMutated {}");
        runGit(masterRepoDir, "add", ".");
        runGit(masterRepoDir, "commit", "-m", "Master mutated commit");

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCommitted());
        assertEquals(GitMutationStatus.BASE_SHA_MISMATCH, result.status());
    }

    @Test
    @DisplayName("9. Path Traversal in Approved File: Returns PATH_VIOLATION if approved path contains relative traversal")
    void testCreateBranchAndCommit_PathTraversalApprovedFile() {
        ApprovedChangeVerificationResult traversalVerification = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, review.getId(),
                1, baseSha, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), "review_hash",
                List.of("../Outside.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(traversalVerification);

        MutationIntegrityResult integrity = MutationIntegrityResult.verified(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                "master_digest", "workspace_digest", List.of("../Outside.java")
        );
        when(mutationIntegrityVerifier.verifyMutationIntegrity(any(), any(), any(), any(), any(), any(), any())).thenReturn(integrity);

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCommitted());
        assertEquals(GitMutationStatus.PATH_VIOLATION, result.status());
    }

    @Test
    @DisplayName("10. Absolute Path in Approved File: Returns PATH_VIOLATION if path is absolute")
    void testCreateBranchAndCommit_AbsolutePathApprovedFile() {
        ApprovedChangeVerificationResult absVerification = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, review.getId(),
                1, baseSha, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), "review_hash",
                List.of("/etc/passwd"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(absVerification);

        MutationIntegrityResult integrity = MutationIntegrityResult.verified(
                operationId, approvalId, projectId, repositoryId, taskId, codeChangeId,
                "master_digest", "workspace_digest", List.of("/etc/passwd")
        );
        when(mutationIntegrityVerifier.verifyMutationIntegrity(any(), any(), any(), any(), any(), any(), any())).thenReturn(integrity);

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isCommitted());
        assertEquals(GitMutationStatus.PATH_VIOLATION, result.status());
    }

    @Test
    @DisplayName("11. Authoritative Default Branch SHA: Resolves base SHA from default branch when HEAD points to feature branch")
    void testCreateBranchAndCommit_AuthoritativeDefaultBranchWhenHeadIsOnFeatureBranch() throws IOException {
        runGit(masterRepoDir, "checkout", "-b", "feature/unrelated");
        Files.writeString(masterRepoDir.resolve("Feature.java"), "class Feature {}");
        runGit(masterRepoDir, "add", ".");
        runGit(masterRepoDir, "commit", "-m", "Unrelated feature commit");

        String featureHeadSha = runGit(masterRepoDir, "rev-parse", "HEAD").trim();
        assertNotEquals(baseSha, featureHeadSha);

        repository.setDefaultBranch("main");

        GitMutationResult result = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isCommitted());
        assertEquals(GitMutationStatus.COMMITTED, result.status());
        assertEquals(baseSha, result.parentCommitSha());
    }

    @Test
    @DisplayName("12. Crash Recovery & Idempotency: Retrying after successful commit returns COMMITTED without creating duplicate commit")
    void testCreateBranchAndCommit_CrashRecoveryPostCommitIdempotent() {
        GitMutationResult firstResult = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertTrue(firstResult.isCommitted());
        String initialCommitSha = firstResult.commitSha();

        GitMutationResult retryResult = boundary.createBranchAndCommit(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(retryResult);
        assertTrue(retryResult.isCommitted());
        assertEquals(GitMutationStatus.COMMITTED, retryResult.status());
        assertEquals(initialCommitSha, retryResult.commitSha());
        assertEquals(firstResult.branchName(), retryResult.branchName());
    }

    @Test
    @DisplayName("13. Network Isolation & Config Injection Attack: Zero HTTP requests made even with injected git config remote/helper")
    void testCreateBranchAndCommit_NetworkIsolatedAgainstInjectedConfig() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requestCount = new java.util.concurrent.atomic.AtomicInteger(0);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            byte[] response = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();

        int port = server.getAddress().getPort();
        try {
            runGit(workspaceRepoDir, "config", "remote.origin.url", "http://127.0.0.1:" + port + "/malicious/leak");
            runGit(workspaceRepoDir, "config", "credential.helper", "!curl http://127.0.0.1:" + port + "/credential");

            GitMutationResult result = boundary.createBranchAndCommit(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );

            assertTrue(result.isCommitted());
            assertEquals(0, requestCount.get(), "Zero network HTTP requests must hit the server");
        } finally {
            server.stop(0);
        }
    }

    private void copyDirectoryRecursively(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(source -> {
            try {
                Path destination = dest.resolve(src.relativize(source));
                if (Files.isDirectory(source)) {
                    if (!Files.exists(destination)) Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private String runGit(Path dir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        try {
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            throw new RuntimeException("Git failed", e);
        }
    }

    private String hashString(String input) {
        if (input == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 missing", e);
        }
    }
}
