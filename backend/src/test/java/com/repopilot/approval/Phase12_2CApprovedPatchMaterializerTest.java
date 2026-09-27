package com.repopilot.approval;

import com.repopilot.approval.dto.ApprovedChangeVerificationResult;
import com.repopilot.approval.dto.PatchMaterializationResult;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.service.ApprovedChangeVerifier;
import com.repopilot.approval.service.ApprovedPatchMaterializer;
import com.repopilot.approval.service.ApprovedPatchMaterializerImpl;
import com.repopilot.approval.service.MutationWorkspaceManager;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
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
import org.springframework.http.HttpStatus;

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

class Phase12_2CApprovedPatchMaterializerTest {

    @TempDir
    Path tempStorageDir;

    @TempDir
    Path tempWorkspacesDir;

    private ApprovedChangeVerifier approvedChangeVerifier;
    private HumanApprovalRepository humanApprovalRepository;
    private CodeChangeRepository codeChangeRepository;
    private MutationWorkspaceManager mutationWorkspaceManager;
    private SecretScannerService secretScannerService;
    private ApprovedPatchMaterializer materializer;

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

        materializer = new ApprovedPatchMaterializerImpl(
                approvedChangeVerifier,
                humanApprovalRepository,
                codeChangeRepository,
                mutationWorkspaceManager,
                secretScannerService,
                tempStorageDir.toAbsolutePath().toString()
        );

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        codeChangeId = UUID.randomUUID();
        approvalId = UUID.randomUUID();
        operationId = UUID.randomUUID();

        requester = new User();
        requester.setId(UUID.randomUUID());
        requester.setEmail("approver@example.com");

        masterRepoDir = tempStorageDir.resolve(repositoryId.toString());
        Files.createDirectories(masterRepoDir);

        workspaceDir = tempWorkspacesDir.resolve("mutation-" + approvalId + "-" + operationId);
        workspaceRepoDir = workspaceDir.resolve("repo");
        Files.createDirectories(workspaceRepoDir);

        // Populate baseline file
        Files.writeString(masterRepoDir.resolve("App.java"), "public class App {\n  // Original\n}\n");
        Files.writeString(workspaceRepoDir.resolve("App.java"), "public class App {\n  // Original\n}\n");

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
        codeChange.setStartLine(1);

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
    // A. HAPPY PATH TESTS
    // ==========================================

    @Test
    @DisplayName("1. One-File Replacement: Applies approved patch cleanly to workspace")
    void testMaterializeApprovedPatch_OneFileReplacement() throws IOException {
        PatchMaterializationResult result = materializer.materializeApprovedPatch(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertEquals(operationId, result.operationId());
        assertEquals(approvalId, result.approvalId());
        assertEquals(1, result.appliedFileCount());
        assertEquals("public class App {\n  // Approved Patch\n}\n", Files.readString(workspaceRepoDir.resolve("App.java")));

        // Baseline master remains 100% untouched!
        assertEquals("public class App {\n  // Original\n}\n", Files.readString(masterRepoDir.resolve("App.java")));
    }

    @Test
    @DisplayName("2. Multi-File Replacement / Create Operation: Materializes new approved file")
    void testMaterializeApprovedPatch_CreateFile() throws IOException {
        String newFilePath = "src/Utils.java";
        String newContent = "public class Utils {}\n";
        String newProposedHash = hashString(newContent);
        String diffText = "@@ -0,0 +1,1 @@\n+public class Utils {}\n";
        String diffHash = hashString(diffText);

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

        PatchMaterializationResult result = materializer.materializeApprovedPatch(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertTrue(Files.exists(workspaceRepoDir.resolve("src/Utils.java")));
        assertEquals(newContent, Files.readString(workspaceRepoDir.resolve("src/Utils.java")));
    }

    @Test
    @DisplayName("3. Nested Paths: Modifies nested path deep in folder structure")
    void testMaterializeApprovedPatch_NestedPaths() throws IOException {
        Path deepMaster = masterRepoDir.resolve("src/main/java/com/example");
        Path deepWs = workspaceRepoDir.resolve("src/main/java/com/example");
        Files.createDirectories(deepMaster);
        Files.createDirectories(deepWs);

        Files.writeString(deepMaster.resolve("Service.java"), "class Service {}");
        Files.writeString(deepWs.resolve("Service.java"), "class Service {}");

        String origHash = hashString("class Service {}");
        String newContent = "class Service { void run(){} }";
        String propHash = hashString(newContent);
        String diffText = "class Service { void run(){} }";
        String diffHash = hashString(diffText);

        String filePath = "src/main/java/com/example/Service.java";
        codeChange.setFilePath(filePath);
        codeChange.setOldContent("class Service {}");
        codeChange.setNewContent(newContent);
        codeChange.setOriginalHash(origHash);
        codeChange.setProposedHash(propHash);
        codeChange.setDiff(diffText);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, origHash, propHash, diffHash, filePath, ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", origHash, propHash, diffHash, "review_hash",
                List.of(filePath), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        PatchMaterializationResult result = materializer.materializeApprovedPatch(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertEquals(newContent, Files.readString(deepWs.resolve("Service.java")));
    }

    @Test
    @DisplayName("4. Empty File: Handles 0-byte file replacement cleanly")
    void testMaterializeApprovedPatch_EmptyFile() throws IOException {
        Files.writeString(masterRepoDir.resolve("empty.txt"), "");
        Files.writeString(workspaceRepoDir.resolve("empty.txt"), "");

        String origHash = hashString("");
        String newContent = "added content\n";
        String propHash = hashString(newContent);
        String diffHash = hashString(newContent);

        codeChange.setFilePath("empty.txt");
        codeChange.setOldContent("");
        codeChange.setNewContent(newContent);
        codeChange.setOriginalHash(origHash);
        codeChange.setProposedHash(propHash);
        codeChange.setDiff(newContent);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, origHash, propHash, diffHash, "empty.txt", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", origHash, propHash, diffHash, "review_hash",
                List.of("empty.txt"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        PatchMaterializationResult result = materializer.materializeApprovedPatch(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertEquals(newContent, Files.readString(workspaceRepoDir.resolve("empty.txt")));
    }

    // ==========================================
    // B. HASH BINDING TESTS
    // ==========================================

    @Test
    @DisplayName("6. Diff Hash Mismatch: Throws DIFF_HASH_MISMATCH if computed diff hash != approved diff hash")
    void testMaterializeApprovedPatch_DiffHashMismatch() {
        HumanApproval mismatchApproval = buildApproval(
                approvalId, project, repository, task, codeChange, review,
                codeChange.getOriginalHash(), codeChange.getProposedHash(),
                "tampered_diff_hash_00000000000000000000000000000000000000000000",
                "App.java", ApprovalStatus.APPROVED
        );
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(mismatchApproval));

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("DIFF_HASH_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("7. Original Hash Mismatch: Throws ORIGINAL_HASH_MISMATCH if workspace file content altered")
    void testMaterializeApprovedPatch_OriginalHashMismatch() throws IOException {
        Files.writeString(workspaceRepoDir.resolve("App.java"), "public class App {\n  // ALTERED BEFORE PATCH\n}\n");

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("ORIGINAL_HASH_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("8. Proposed Hash Mismatch: Throws PROPOSED_HASH_MISMATCH if resulting content hash mismatch")
    void testMaterializeApprovedPatch_ProposedHashMismatch() {
        codeChange.setProposedHash("tampered_proposed_hash_000000000000000000000000000000000000000");

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("PROPOSED_HASH_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("10. Stale Approval: Throws APPROVAL_NOT_ACTIVE if approval is not APPROVED")
    void testMaterializeApprovedPatch_StaleApproval() {
        HumanApproval rejectedApproval = buildApproval(
                approvalId, project, repository, task, codeChange, review,
                codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(),
                "App.java", ApprovalStatus.REJECTED
        );
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(rejectedApproval));

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("APPROVAL_NOT_ACTIVE", ex.getCode());
    }

    // ==========================================
    // C. PATH ATTACK TESTS
    // ==========================================

    @Test
    @DisplayName("12. Path Traversal Dots: Rejects path containing ..")
    void testMaterializeApprovedPatch_PathTraversal_Dots() {
        String badPath = "../outside.txt";
        codeChange.setFilePath(badPath);
        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), badPath, ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("PATH_TRAVERSAL_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("13. Path Traversal Encoded: Rejects encoded %2e%2e traversal")
    void testMaterializeApprovedPatch_PathTraversal_Encoded() {
        String badPath = "%2e%2e/outside.txt";
        codeChange.setFilePath(badPath);
        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), badPath, ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("PATH_TRAVERSAL_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("14. Path Traversal Absolute Unix: Rejects leading slash")
    void testMaterializeApprovedPatch_PathTraversal_AbsoluteUnix() {
        String badPath = "/etc/passwd";
        codeChange.setFilePath(badPath);
        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), badPath, ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("PATH_TRAVERSAL_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("17. Path Traversal Null Byte: Rejects null byte in filename")
    void testMaterializeApprovedPatch_PathTraversal_NullByte() {
        String badPath = "App\0.java";
        codeChange.setFilePath(badPath);
        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), badPath, ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("PATH_TRAVERSAL_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("22. Symlink Target Inside: Rejects patch targeting a symlink")
    void testMaterializeApprovedPatch_SymlinkTargetInside() throws IOException {
        Path targetFile = workspaceRepoDir.resolve("target.txt");
        Files.writeString(targetFile, "content");

        Path symlink = workspaceRepoDir.resolve("symlink.txt");
        try {
            Files.createSymbolicLink(symlink, targetFile);
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }

        codeChange.setFilePath("symlink.txt");
        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, "hash", "hash2", approval.getCodeChangeDiffHash(), "symlink.txt", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", "hash", "hash2", approval.getCodeChangeDiffHash(), "review_hash",
                List.of("symlink.txt"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("PATH_SECURITY_VIOLATION", ex.getCode());
    }

    // ==========================================
    // D. PATCH ATTACK & SCOPE TESTS
    // ==========================================

    @Test
    @DisplayName("26. Wrong Context: Throws PATCH_HUNK_MISMATCH if diff context does not match file")
    void testMaterializeApprovedPatch_WrongContext() {
        String badDiff = "@@ -1,3 +1,3 @@\n public class App {\n-  // NON_EXISTENT_CONTEXT_LINE\n+  // Patch\n }\n";
        String newDiffHash = hashString(badDiff);
        codeChange.setDiff(badDiff);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), codeChange.getProposedHash(), newDiffHash, "App.java", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", codeChange.getOriginalHash(), codeChange.getProposedHash(), newDiffHash, "review_hash",
                List.of("App.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("PATCH_HUNK_MISMATCH", ex.getCode());
    }

    @Test
    @DisplayName("34. Unapproved File: Throws UNAPPROVED_FILE_PATH if file path not in approvedFiles")
    void testMaterializeApprovedPatch_UnapprovedFile() {
        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), "OtherFile.java", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", codeChange.getOriginalHash(), codeChange.getProposedHash(), approval.getCodeChangeDiffHash(), "review_hash",
                List.of("OtherFile.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("UNAPPROVED_FILE_PATH", ex.getCode());
    }

    // ==========================================
    // G. SECRET SECURITY TESTS
    // ==========================================

    @Test
    @DisplayName("50. Introduced GitHub Token: Throws SECRET_DETECTED if patch introduces GitHub PAT")
    void testMaterializeApprovedPatch_IntroducedGitHubToken() {
        String secretPatch = "public class App {\n  String token = \"ghp_123456789012345678901234567890123456\";\n}\n";
        String secretHash = hashString(secretPatch);
        String secretDiffHash = hashString(secretPatch);

        codeChange.setNewContent(secretPatch);
        codeChange.setProposedHash(secretHash);
        codeChange.setDiff(secretPatch);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), secretHash, secretDiffHash, "App.java", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", codeChange.getOriginalHash(), secretHash, secretDiffHash, "review_hash",
                List.of("App.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("SECRET_DETECTED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("51. AWS Secret Key: Throws SECRET_DETECTED if patch contains AWS Key")
    void testMaterializeApprovedPatch_AWSSecretKey() {
        String secretPatch = "public class App {\n  String key = \"AKIAIOSFODNN7EXAMPLE\";\n}\n";
        String secretHash = hashString(secretPatch);
        String secretDiffHash = hashString(secretPatch);

        codeChange.setNewContent(secretPatch);
        codeChange.setProposedHash(secretHash);
        codeChange.setDiff(secretPatch);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), secretHash, secretDiffHash, "App.java", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", codeChange.getOriginalHash(), secretHash, secretDiffHash, "review_hash",
                List.of("App.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("SECRET_DETECTED", ex.getCode());
    }

    // ==========================================
    // I. SIDE EFFECT & LIFECYCLE TESTS
    // ==========================================

    @Test
    @DisplayName("60. Cleanup After Failure: Cleans up workspace if materialization fails")
    void testMaterializeApprovedPatch_CleanupAfterFailure() {
        codeChange.setProposedHash("corrupted_hash");

        assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        verify(mutationWorkspaceManager, times(1)).cleanupMutationWorkspace(operationId);
    }

    @Test
    @DisplayName("36. Delete File: Deletes approved target file safely from workspace")
    void testMaterializeApprovedPatch_DeleteFile() throws IOException {
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

        PatchMaterializationResult result = materializer.materializeApprovedPatch(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        assertNotNull(result);
        assertFalse(Files.exists(workspaceRepoDir.resolve("App.java")));
        // Master baseline file remains present and untouched!
        assertTrue(Files.exists(masterRepoDir.resolve("App.java")));
    }

    @Test
    @DisplayName("52. Private Key Secret: Throws SECRET_DETECTED if patch contains RSA Private Key")
    void testMaterializeApprovedPatch_PrivateKeySecret() {
        String secretPatch = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA...\n-----END RSA PRIVATE KEY-----";
        String secretHash = hashString(secretPatch);
        String secretDiffHash = hashString(secretPatch);

        codeChange.setNewContent(secretPatch);
        codeChange.setProposedHash(secretHash);
        codeChange.setDiff(secretPatch);

        HumanApproval freshApproval = buildApproval(approvalId, project, repository, task, codeChange, review, codeChange.getOriginalHash(), secretHash, secretDiffHash, "App.java", ApprovalStatus.APPROVED);
        when(humanApprovalRepository.findById(approvalId)).thenReturn(Optional.of(freshApproval));

        ApprovedChangeVerificationResult validResult = ApprovedChangeVerificationResult.valid(
                approvalId, projectId, repositoryId, taskId, codeChangeId, UUID.randomUUID(),
                1, "base_sha", codeChange.getOriginalHash(), secretHash, secretDiffHash, "review_hash",
                List.of("App.java"), "valid_record_hash", requester.getId()
        );
        when(approvedChangeVerifier.verifyApprovedChange(any(), any(), any(), any(), any(), any())).thenReturn(validResult);

        ApiException ex = assertThrows(ApiException.class, () -> {
            materializer.materializeApprovedPatch(
                    approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
            );
        });

        assertEquals("SECRET_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("66. Zero Baseline Mutation: Proves master baseline repo remains 100% untouched")
    void testMaterializeApprovedPatch_ZeroBaselineMutation() throws IOException {
        materializer.materializeApprovedPatch(
                approvalId, operationId, projectId, repositoryId, taskId, codeChangeId, requester
        );

        // Master baseline file remains 100% original content
        assertEquals("public class App {\n  // Original\n}\n", Files.readString(masterRepoDir.resolve("App.java")));
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
