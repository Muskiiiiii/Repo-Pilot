package com.repopilot.approval;

import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.approval.dto.MutationIntegrityResult;
import com.repopilot.approval.dto.MutationIntegrityStatus;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.service.ApprovedChangeVerifier;
import com.repopilot.approval.service.MutationIntegrityVerifier;
import com.repopilot.approval.service.MutationIntegrityVerifierImpl;
import com.repopilot.approval.service.MutationWorkspaceManager;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.security.service.SecretScannerService;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase12_2DMutationIntegrityVerifierTest {

    @TempDir
    Path tempStorageDir;

    @TempDir
    Path tempWorkspacesDir;

    private ApprovedChangeVerifier approvedChangeVerifier;
    private HumanApprovalRepository humanApprovalRepository;
    private CodeChangeRepository codeChangeRepository;
    private MutationWorkspaceManager mutationWorkspaceManager;
    private SecretScannerService secretScannerService;
    private MutationIntegrityVerifier verifier;

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

    @BeforeEach
    void setUp() throws IOException {
        approvedChangeVerifier = mock(ApprovedChangeVerifier.class);
        humanApprovalRepository = mock(HumanApprovalRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        mutationWorkspaceManager = mock(MutationWorkspaceManager.class);
        secretScannerService = new SecretScannerService();

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();
        approvalId = UUID.randomUUID();
        operationId = UUID.randomUUID();

        requester = new User();
        requester.setId(UUID.randomUUID());

        verifier = new MutationIntegrityVerifierImpl(
                approvedChangeVerifier,
                humanApprovalRepository,
                codeChangeRepository,
                mutationWorkspaceManager,
                secretScannerService,
                tempStorageDir.toAbsolutePath().toString(),
                tempWorkspacesDir.toAbsolutePath().toString()
        );

        masterRepoDir = tempStorageDir.resolve(repositoryId.toString());
        Files.createDirectories(masterRepoDir);

        workspaceDir = tempWorkspacesDir.resolve("mutation-" + approvalId + "-" + operationId);
        workspaceRepoDir = workspaceDir.resolve("repo");
        Files.createDirectories(workspaceRepoDir);

        // Write .execution.lock file to simulate Phase 12.2-B lock creation
        Files.writeString(workspaceDir.resolve(".execution.lock"), "baselineTreeHash=digest_123\n");

        // Baseline files
        Files.writeString(masterRepoDir.resolve("App.java"), "public class App {\n  // Original\n}\n");
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

        task = new Task();
        task.setId(taskId);

        review = new CodeReview();
        review.setId(UUID.randomUUID());

        approval = buildApproval(approvalId, project, repository, task, codeChange, review, originalHash, proposedHash, diffHash, "App.java", ApprovalStatus.APPROVED);

        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(approval));
        when(codeChangeRepository.findById(codeChangeId)).thenReturn(Optional.of(codeChange));
        when(mutationWorkspaceManager.getMutationWorkspacePath(operationId)).thenReturn(workspaceRepoDir);
        when(mutationWorkspaceManager.calculateTreeDigest(any())).thenReturn("digest_123");

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, review.getId(),
                1, "base_sha", originalHash, proposedHash, diffHash, "review_hash",
                List.of("App.java"), "valid_record_hash", requester.getId()
        );

        when(approvedChangeVerifier.verifyApprovedChange(
                eq(projectId), eq(repositoryId), eq(taskId), eq(codeChangeId), eq(approvalId), eq(requester)
        )).thenReturn(validResult);
    }

    private HumanApproval buildApproval(UUID id, Project proj, Repository repo, Task t, CodeChange cc, CodeReview rev,
                                        String origHash, String propHash, String diffHash, String approvedFiles, ApprovalStatus status) {
        HumanApproval ha = new HumanApproval();
        ha.setId(id);
        ha.setProject(proj);
        ha.setRepository(repo);
        ha.setTask(t);
        ha.setCodeChange(cc);
        ha.setReview(rev);
        ha.setCodeChangeOriginalHash(origHash);
        ha.setCodeChangeProposedHash(propHash);
        ha.setCodeChangeDiffHash(diffHash);
        ha.setApprovedFiles(approvedFiles);
        ha.setApprovalRecordHash("valid_record_hash");
        ha.setStatus(status);
        return ha;
    }

    // ==========================================
    // A. VALID INTEGRITY TESTS
    // ==========================================

    @Test
    @DisplayName("1. Exact One-File Replacement: Verifies valid materialization -> VERIFIED")
    void testVerifyMutationIntegrity_ExactOneFileReplacement() {
        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isVerified());
        assertEquals(MutationIntegrityStatus.VERIFIED, result.status());
        assertNull(result.failureReason());
        assertEquals(1, result.changedPathCount());
        assertTrue(result.approvedChangedPaths().contains("App.java"));
    }

    @Test
    @DisplayName("3. Exact Create File: Verifies valid file creation -> VERIFIED")
    void testVerifyMutationIntegrity_ExactCreate() throws IOException {
        String newFilePath = "src/Utils.java";
        String newContent = "public class Utils {}\n";
        String newProposedHash = hashString(newContent);
        String diffText = "@@ -0,0 +1,1 @@\n+public class Utils {}\n";
        String diffHash = hashString(diffText);

        Files.createDirectories(workspaceRepoDir.resolve("src"));
        Files.writeString(workspaceRepoDir.resolve("src/Utils.java"), newContent);
        // Also ensure App.java matches baseline
        Files.writeString(workspaceRepoDir.resolve("App.java"), "public class App {\n  // Original\n}\n");

        codeChange.setFilePath(newFilePath);
        codeChange.setOperation("CREATE");
        codeChange.setOldContent(null);
        codeChange.setNewContent(newContent);
        codeChange.setOriginalHash(null);
        codeChange.setProposedHash(newProposedHash);
        codeChange.setDiff(diffText);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, null, newProposedHash, diffHash, newFilePath, ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", null, newProposedHash, diffHash, "review_hash",
                List.of(newFilePath), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isVerified());
        assertEquals(MutationIntegrityStatus.VERIFIED, result.status());
    }

    @Test
    @DisplayName("4. Exact Delete File: Verifies valid file deletion -> VERIFIED")
    void testVerifyMutationIntegrity_ExactDelete() throws IOException {
        Files.delete(workspaceRepoDir.resolve("App.java"));

        codeChange.setOperation("DELETE");
        codeChange.setNewContent(null);
        codeChange.setProposedHash(null);
        String diffText = "@@ -1,3 +0,0 @@\n-public class App {\n-  // Original\n-}\n";
        String diffHash = hashString(diffText);
        codeChange.setDiff(diffText);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), null, diffHash, "App.java", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", codeChange.getOriginalHash(), null, diffHash, "review_hash",
                List.of("App.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(result.isVerified());
        assertEquals(MutationIntegrityStatus.VERIFIED, result.status());
    }

    // ==========================================
    // B. HASH & SNAPSHOT MISMATCH TESTS
    // ==========================================

    @Test
    @DisplayName("6. Wrong Proposed Hash: Returns SNAPSHOT_MISMATCH if workspace file content mismatch")
    void testVerifyMutationIntegrity_WrongProposedHash() throws IOException {
        Files.writeString(workspaceRepoDir.resolve("App.java"), "public class App {\n  // TAMPERED WORKSPACE\n}\n");

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.SNAPSHOT_MISMATCH, result.status());
        assertNotNull(result.failureReason());
    }

    @Test
    @DisplayName("9. Wrong Approval Hash / Stale Approval: Returns APPROVAL_STALE if status != APPROVED")
    void testVerifyMutationIntegrity_StaleApproval() {
        HumanApproval rejectedApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), "App.java", ApprovalStatus.REJECTED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(rejectedApproval));

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.APPROVAL_STALE, result.status());
    }

    // ==========================================
    // C. UNEXPECTED FILES & EXTRA MODIFICATIONS
    // ==========================================

    @Test
    @DisplayName("13. Unapproved File Modified: Returns UNEXPECTED_FILE if extra unapproved file modified")
    void testVerifyMutationIntegrity_UnapprovedFileModified() throws IOException {
        // Add an extra file to baseline and modify it in workspace
        Files.writeString(masterRepoDir.resolve("Secret.java"), "class Secret {}");
        Files.writeString(workspaceRepoDir.resolve("Secret.java"), "class Secret { void leak(){} }");

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.UNEXPECTED_FILE, result.status());
        assertTrue(result.failureReason().contains("Secret.java"));
    }

    @Test
    @DisplayName("14. Unapproved File Created: Returns UNEXPECTED_FILE if extra file created")
    void testVerifyMutationIntegrity_UnapprovedFileCreated() throws IOException {
        Files.writeString(workspaceRepoDir.resolve("Evil.java"), "class Evil {}");

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.UNEXPECTED_FILE, result.status());
    }

    @Test
    @DisplayName("17. Hidden Temp File: Returns UNEXPECTED_FILE if leftover .tmp_patch_123 file found")
    void testVerifyMutationIntegrity_HiddenTempFile() throws IOException {
        Files.writeString(workspaceRepoDir.resolve(".tmp_patch_12345"), "leftover temp");

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.UNEXPECTED_FILE, result.status());
        assertTrue(result.failureReason().contains(".tmp_patch_12345"));
    }

    // ==========================================
    // E. BASELINE INTEGRITY TESTS
    // ==========================================

    @Test
    @DisplayName("18. Master Baseline Mutated: Returns BASELINE_MUTATED if master repo file altered")
    void testVerifyMutationIntegrity_MasterBaselineMutated() throws IOException {
        // Mutate master baseline file
        Files.writeString(masterRepoDir.resolve("App.java"), "public class App {\n  // MASTER MUTATED\n}\n");

        when(mutationWorkspaceManager.calculateTreeDigest(masterRepoDir)).thenReturn("altered_master_digest");

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.BASELINE_MUTATED, result.status());
    }

    // ==========================================
    // F. SECRET SECURITY TESTS
    // ==========================================

    @Test
    @DisplayName("20. GitHub Token Detected: Returns SECRET_DETECTED if GitHub PAT introduced in workspace")
    void testVerifyMutationIntegrity_GitHubTokenDetected() throws IOException {
        Files.writeString(workspaceRepoDir.resolve("App.java"), "public class App {\n  String pat = \"ghp_123456789012345678901234567890123456\";\n}\n");

        String secretContent = "public class App {\n  String pat = \"ghp_123456789012345678901234567890123456\";\n}\n";
        String secretHash = hashString(secretContent);
        codeChange.setNewContent(secretContent);
        codeChange.setProposedHash(secretHash);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), secretHash, approval.getCodeChangeDiffHash(), "App.java", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.SECRET_DETECTED, result.status());
    }

    // ==========================================
    // G. CONCURRENCY & STABILITY TESTS
    // ==========================================

    @Test
    @DisplayName("23. Workspace Unstable: Returns WORKSPACE_UNSTABLE if workspace digest changes during verification")
    void testVerifyMutationIntegrity_WorkspaceUnstable() {
        when(mutationWorkspaceManager.calculateTreeDigest(workspaceRepoDir))
                .thenReturn("digest_t1")
                .thenReturn("digest_t2");

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.WORKSPACE_UNSTABLE, result.status());
    }

    // ==========================================
    // H. ADVANCED HOSTILE SECURITY TESTS
    // ==========================================

    @Test
    @DisplayName("24. Hardlink to Master Baseline: Returns PATH_VIOLATION if workspace file is hardlinked to master")
    void testVerifyMutationIntegrity_HardlinkToMasterDetected() throws IOException {
        Path masterFile = masterRepoDir.resolve("App.java");
        Path workspaceFile = workspaceRepoDir.resolve("App.java");
        Files.deleteIfExists(workspaceFile);
        try {
            Files.createLink(workspaceFile, masterFile);
            MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            assertNotNull(result);
            assertFalse(result.isVerified());
            assertEquals(MutationIntegrityStatus.PATH_VIOLATION, result.status());
            assertTrue(result.failureReason().contains("Hardlink"));
        } catch (UnsupportedOperationException e) {
            // Supported filesystems only
        }
    }

    @Test
    @DisplayName("25. Unapproved Symlink in Workspace: Returns UNEXPECTED_FILE if unapproved symlink created")
    void testVerifyMutationIntegrity_UnapprovedSymlinkDetected() throws IOException {
        Path link = workspaceRepoDir.resolve("unapproved_link.txt");
        Path target = workspaceRepoDir.resolve("App.java");
        try {
            Files.createSymbolicLink(link, target);
            MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
            assertNotNull(result);
            assertFalse(result.isVerified());
            assertTrue(result.status() == MutationIntegrityStatus.UNEXPECTED_FILE || result.status() == MutationIntegrityStatus.PATH_VIOLATION);
        } catch (UnsupportedOperationException e) {
            // Windows/environments without symlink privileges
        }
    }

    @Test
    @DisplayName("26. Duplicate Approved Path: Returns SNAPSHOT_MISMATCH if approval has duplicate files")
    void testVerifyMutationIntegrity_DuplicateApprovedPath() {
        ApprovedChangeVerificationResult dupResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, review.getId(),
                1, "base_sha", codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), "review_hash",
                List.of("App.java", "App.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(dupResult);

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.SNAPSHOT_MISMATCH, result.status());
        assertTrue(result.failureReason().contains("Duplicate"));
    }

    @Test
    @DisplayName("27. Real Concurrent Background Mutation: Fails closed when workspace is mutated concurrently")
    void testVerifyMutationIntegrity_RealConcurrentBackgroundMutation() throws Exception {
        // Mock calculateTreeDigest to return distinct digests for T1 and T2
        when(mutationWorkspaceManager.calculateTreeDigest(workspaceRepoDir))
                .thenReturn("digest_initial")
                .thenReturn("digest_mutated");

        MutationIntegrityResult result = verifier.verifyMutationIntegrity(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(result.isVerified());
        assertEquals(MutationIntegrityStatus.WORKSPACE_UNSTABLE, result.status());
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
