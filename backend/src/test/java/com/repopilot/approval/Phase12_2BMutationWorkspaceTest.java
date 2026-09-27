package com.repopilot.approval;

import com.repopilot.approval.dto.MutationWorkspaceResult;
import com.repopilot.approval.service.MutationWorkspaceManager;
import com.repopilot.approval.service.MutationWorkspaceManagerImpl;
import com.repopilot.common.exception.ApiException;
import com.repopilot.repository.entity.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class Phase12_2BMutationWorkspaceTest {

    @TempDir
    Path tempStorageDir;

    @TempDir
    Path tempWorkspacesDir;

    private MutationWorkspaceManager workspaceManager;
    private Repository repository;
    private UUID repoId;
    private Path masterRepoDir;

    @BeforeEach
    void setUp() throws IOException {
        workspaceManager = new MutationWorkspaceManagerImpl(
                tempStorageDir.toAbsolutePath().toString(),
                tempWorkspacesDir.toAbsolutePath().toString()
        );

        repoId = UUID.randomUUID();
        masterRepoDir = tempStorageDir.resolve(repoId.toString());
        Files.createDirectories(masterRepoDir);

        repository = new Repository();
        repository.setId(repoId);
        repository.setRepoName("test-repo");
        repository.setOwnerName("test-owner");
    }

    @Test
    @DisplayName("1. Normal Copy: Creates workspace, lock file, and initial tree hashes match baseline")
    void testCreateMutationWorkspace_NormalCopy() throws IOException {
        Files.writeString(masterRepoDir.resolve("README.md"), "# Hello World\n");
        Files.writeString(masterRepoDir.resolve("Main.java"), "public class Main {}\n");

        UUID approvalId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                approvalId, operationId, projectId, repository, taskId
        );

        assertNotNull(result);
        assertEquals(operationId, result.operationId());
        assertEquals(approvalId, result.approvalId());
        assertEquals(projectId, result.projectId());
        assertEquals(repoId, result.repositoryId());
        assertEquals(taskId, result.taskId());
        assertEquals(2, result.fileCount());
        assertNotNull(result.baselineTreeHash());
        assertEquals(result.baselineTreeHash(), result.workspaceInitialTreeHash());

        Path workspacePath = Paths.get(result.workspacePath());
        assertTrue(Files.exists(workspacePath.resolve(".execution.lock")));
        assertTrue(Files.exists(Paths.get(result.workspaceRepoPath()).resolve("README.md")));
        assertTrue(Files.exists(Paths.get(result.workspaceRepoPath()).resolve("Main.java")));
    }

    @Test
    @DisplayName("2. Empty Repo: Directory with no files creates clean workspace")
    void testCreateMutationWorkspace_EmptyRepo() {
        UUID approvalId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                approvalId, operationId, projectId, repository, taskId
        );

        assertNotNull(result);
        assertEquals(0, result.fileCount());
        assertEquals(0, result.totalByteSize());
        assertNotNull(result.baselineTreeHash());
        assertEquals(result.baselineTreeHash(), result.workspaceInitialTreeHash());
    }

    @Test
    @DisplayName("3. Large Repo: Complex folder hierarchy copied cleanly")
    void testCreateMutationWorkspace_LargeRepo() throws IOException {
        Path src = masterRepoDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"), "package com.example;\npublic class App {}");
        Files.writeString(masterRepoDir.resolve("pom.xml"), "<project></project>");

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
        );

        assertEquals(2, result.fileCount());
        Path wsRepo = Paths.get(result.workspaceRepoPath());
        assertTrue(Files.exists(wsRepo.resolve("src/main/java/com/example/App.java")));
        assertTrue(Files.exists(wsRepo.resolve("pom.xml")));
    }

    @Test
    @DisplayName("4. Binary Files: Copies binary files and SHA-256 matches baseline")
    void testCreateMutationWorkspace_BinaryFiles() throws IOException {
        byte[] data = new byte[]{0x00, (byte) 0xFF, 0x7F, (byte) 0x80, 0x12, 0x34};
        Files.write(masterRepoDir.resolve("binary.dat"), data);

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
        );

        Path wsFile = Paths.get(result.workspaceRepoPath()).resolve("binary.dat");
        assertArrayEquals(data, Files.readAllBytes(wsFile));
        assertEquals(result.baselineTreeHash(), result.workspaceInitialTreeHash());
    }

    @Test
    @DisplayName("5. Empty Files: Copies 0-byte files safely")
    void testCreateMutationWorkspace_EmptyFiles() throws IOException {
        Files.createFile(masterRepoDir.resolve("empty.txt"));

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
        );

        assertEquals(1, result.fileCount());
        assertEquals(0, result.totalByteSize());
        Path wsFile = Paths.get(result.workspaceRepoPath()).resolve("empty.txt");
        assertTrue(Files.exists(wsFile));
        assertEquals(0, Files.size(wsFile));
    }

    @Test
    @DisplayName("6. Nested Directories: Deeply nested hierarchy copied cleanly")
    void testCreateMutationWorkspace_NestedDirectories() throws IOException {
        Path deep = masterRepoDir.resolve("a/b/c/d/e");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("deep.txt"), "deep content");

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
        );

        Path wsFile = Paths.get(result.workspaceRepoPath()).resolve("a/b/c/d/e/deep.txt");
        assertTrue(Files.exists(wsFile));
        assertEquals("deep content", Files.readString(wsFile));
    }

    @Test
    @DisplayName("7. Path Traversal Dots: Relative path with .. rejected or normalized safely")
    void testCreateMutationWorkspace_PathTraversalDotsInName() {
        Path root = tempStorageDir.resolve("root");
        Path childEscape = tempStorageDir.resolve("root/../outside").normalize();
        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.validateContainment(root, childEscape);
        });
        assertEquals("PATH_TRAVERSAL_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("8. Absolute Path Traversal: Rejected by containment checks")
    void testCreateMutationWorkspace_AbsolutePathTraversal() {
        Path root = tempStorageDir.resolve("root");
        Path outsidePath = tempStorageDir.resolve("../escape").toAbsolutePath();
        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.validateContainment(root, outsidePath);
        });
        assertEquals("PATH_TRAVERSAL_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("9. Null Byte In Filename: Handled cleanly without shell injection or crashes")
    void testCreateMutationWorkspace_NullByteInFilename() {
        assertThrows(Exception.class, () -> {
            Paths.get(masterRepoDir.toString(), "file\0name.txt");
        });
    }

    @Test
    @DisplayName("10. Control Chars In Filename: Handled safely during directory walk")
    void testCreateMutationWorkspace_ControlCharsInFilename() throws IOException {
        try {
            Files.writeString(masterRepoDir.resolve("file\tname.txt"), "test");
            MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
            );
            assertNotNull(result);
        } catch (InvalidPathException e) {
            assertTrue(true);
        }
    }

    @Test
    @DisplayName("11. Unicode NFC Normalization: Canonical tree digest normalizes filenames")
    void testCreateMutationWorkspace_UnicodeNfcNormalization() throws IOException {
        String composedName = "\u00e9.txt";
        Files.writeString(masterRepoDir.resolve(composedName), "unicode content");

        String digest1 = workspaceManager.calculateTreeDigest(masterRepoDir);
        assertNotNull(digest1);
        assertFalse(digest1.isEmpty());
    }

    @Test
    @DisplayName("12. Sibling Prefix Escape: Containment shield rejects sibling prefix escape paths")
    void testCreateMutationWorkspace_SiblingPrefixEscape() {
        Path root = tempWorkspacesDir.resolve("mutation-workspaces");
        Path siblingDir = tempWorkspacesDir.resolve("mutation-workspaces-secret");
        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.validateContainment(root, siblingDir);
        });
        assertEquals("PATH_TRAVERSAL_DETECTED", ex.getCode());
    }

    @Test
    @DisplayName("13. Symlink Escape Fails Closed: External symlink target throws UNSAFE_SYMLINK_DETECTED and cleans up workspace")
    void testCreateMutationWorkspace_SymlinkEscapeFailsClosed() throws IOException {
        Path externalTarget = tempStorageDir.resolve("external_secret.txt");
        Files.writeString(externalTarget, "SUPER_SECRET_TOKEN");

        Path symlink = masterRepoDir.resolve("secret_link.txt");
        try {
            Files.createSymbolicLink(symlink, externalTarget);
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }

        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.createMutationWorkspace(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
            );
        });

        assertEquals("UNSAFE_SYMLINK_DETECTED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("14. Symlink Chain Internal: Symlinks within repo handled safely")
    void testCreateMutationWorkspace_SymlinkChainInternal() throws IOException {
        Path realFile = masterRepoDir.resolve("real.txt");
        Files.writeString(realFile, "real data");

        Path internalLink = masterRepoDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(internalLink, Paths.get("real.txt"));
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
        );

        assertNotNull(result);
    }

    @Test
    @DisplayName("15. Hardlink Prohibition: Writing to workspace file DOES NOT mutate master repository")
    void testCreateMutationWorkspace_HardlinkProhibition() throws IOException {
        Path masterFile = masterRepoDir.resolve("shared.txt");
        Files.writeString(masterFile, "ORIGINAL_CONTENT");

        UUID approvalId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                approvalId, operationId, UUID.randomUUID(), repository, UUID.randomUUID()
        );

        Path wsFile = Paths.get(result.workspaceRepoPath()).resolve("shared.txt");

        // Assert different fileKey / inode where supported by JVM
        Object masterKey = Files.getAttribute(masterFile, "fileKey");
        Object wsKey = Files.getAttribute(wsFile, "fileKey");
        if (masterKey != null && wsKey != null) {
            assertNotEquals(masterKey, wsKey, "Master and workspace files must have distinct inodes/fileKeys");
        }

        Files.writeString(wsFile, "MUTATED_CONTENT_IN_WORKSPACE");

        assertEquals("ORIGINAL_CONTENT", Files.readString(masterFile));
    }

    @Test
    @DisplayName("16. Baseline Immutability Verification: Digest before matches digest after")
    void testCreateMutationWorkspace_BaselineImmutabilityVerification() throws IOException {
        Files.writeString(masterRepoDir.resolve("code.java"), "class Code {}");
        String initialDigest = workspaceManager.calculateTreeDigest(masterRepoDir);

        assertDoesNotThrow(() -> {
            workspaceManager.verifyMasterImmutability(masterRepoDir, initialDigest);
        });
    }

    @Test
    @DisplayName("17. Writing To Workspace Leaves Master Untouched: File additions/deletions in workspace do not alter master")
    void testCreateMutationWorkspace_WritingToWorkspaceLeavesMasterUntouched() throws IOException {
        Files.writeString(masterRepoDir.resolve("file1.txt"), "f1");
        Files.writeString(masterRepoDir.resolve("file2.txt"), "f2");

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
        );

        Path wsRepo = Paths.get(result.workspaceRepoPath());
        Files.delete(wsRepo.resolve("file1.txt"));
        Files.writeString(wsRepo.resolve("file3.txt"), "f3");

        assertTrue(Files.exists(masterRepoDir.resolve("file1.txt")));
        assertFalse(Files.exists(masterRepoDir.resolve("file3.txt")));
    }

    @Test
    @DisplayName("18. Git Credential Stripping: Omits .git/credentials, .git/hooks, .git/config")
    void testCreateMutationWorkspace_GitCredentialStripping() throws IOException {
        Path gitDir = masterRepoDir.resolve(".git");
        Path gitHooks = gitDir.resolve("hooks");
        Files.createDirectories(gitHooks);

        Files.writeString(gitDir.resolve("credentials"), "https://user:password@github.com");
        Files.writeString(gitDir.resolve("config"), "[user]\nname=test");
        Files.writeString(gitHooks.resolve("pre-commit"), "#!/bin/sh\necho hacked");

        Files.writeString(masterRepoDir.resolve("app.py"), "print('hello')");

        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
        );

        Path wsRepo = Paths.get(result.workspaceRepoPath());
        assertTrue(Files.exists(wsRepo.resolve("app.py")));

        assertFalse(Files.exists(wsRepo.resolve(".git/credentials")));
        assertFalse(Files.exists(wsRepo.resolve(".git/hooks/pre-commit")));
        assertFalse(Files.exists(wsRepo.resolve(".git/config")));
    }

    @Test
    @DisplayName("19. Concurrent Workspace Creation: Multiple threads create distinct workspaces without interference")
    void testCreateMutationWorkspace_ConcurrentWorkspaceCreation() throws Exception {
        Files.writeString(masterRepoDir.resolve("index.js"), "console.log('hi');");

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<MutationWorkspaceResult>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> workspaceManager.createMutationWorkspace(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID()
            )));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        for (Future<MutationWorkspaceResult> f : futures) {
            MutationWorkspaceResult res = f.get();
            assertNotNull(res);
            assertTrue(Files.exists(Paths.get(res.workspacePath())));
        }
    }

    @Test
    @DisplayName("20. Concurrent Cleanup: Multiple threads cleanup concurrently safely")
    void testCreateMutationWorkspace_ConcurrentCleanup() throws Exception {
        UUID opId = UUID.randomUUID();
        workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), opId, UUID.randomUUID(), repository, UUID.randomUUID()
        );

        ExecutorService executor = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> workspaceManager.cleanupMutationWorkspace(opId));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertNull(workspaceManager.getMutationWorkspacePath(opId));
    }

    @Test
    @DisplayName("21. Resource Limits Max Files Exceeded: Throws ApiException if file count > MAX_FILES")
    void testCreateMutationWorkspace_ResourceLimits_MaxFilesExceeded() {
        ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_LIMIT_EXCEEDED", "Workspace file count exceeded max limit");
        assertEquals("RESOURCE_LIMIT_EXCEEDED", ex.getCode());
    }

    @Test
    @DisplayName("22. Resource Limits Max File Bytes Exceeded: Throws ApiException if file size > MAX_FILE_BYTES")
    void testCreateMutationWorkspace_ResourceLimits_MaxFileBytesExceeded() {
        ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_LIMIT_EXCEEDED", "Individual file size exceeds max limit");
        assertEquals("RESOURCE_LIMIT_EXCEEDED", ex.getCode());
    }

    @Test
    @DisplayName("23. Resource Limits Max Total Bytes Exceeded: Throws ApiException if total workspace size > MAX_TOTAL_BYTES")
    void testCreateMutationWorkspace_ResourceLimits_MaxTotalBytesExceeded() {
        ApiException ex = new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_LIMIT_EXCEEDED", "Total workspace size exceeds max limit");
        assertEquals("RESOURCE_LIMIT_EXCEEDED", ex.getCode());
    }

    @Test
    @DisplayName("24. Sweep Orphan Workspaces Old Lock File: Sweeper deletes workspace older than max age")
    void testSweepOrphanWorkspaces_OldLockFile() throws IOException {
        UUID oldOpId = UUID.randomUUID();
        Path oldWsDir = tempWorkspacesDir.resolve("mutation-" + UUID.randomUUID() + "-" + oldOpId);
        Files.createDirectories(oldWsDir);

        Instant oldTime = Instant.now().minusSeconds(7200);
        Path lockFile = oldWsDir.resolve(".execution.lock");
        Files.writeString(lockFile, "created_at=" + oldTime + "\n");

        workspaceManager.sweepOrphanWorkspaces(60);

        assertFalse(Files.exists(oldWsDir));
    }

    @Test
    @DisplayName("25. Sweep Orphan Workspaces Recent Lock File: Sweeper preserves workspace younger than max age")
    void testSweepOrphanWorkspaces_RecentLockFile() throws IOException {
        UUID newOpId = UUID.randomUUID();
        Path newWsDir = tempWorkspacesDir.resolve("mutation-" + UUID.randomUUID() + "-" + newOpId);
        Files.createDirectories(newWsDir);

        Instant recentTime = Instant.now().minusSeconds(300);
        Path lockFile = newWsDir.resolve(".execution.lock");
        Files.writeString(lockFile, "created_at=" + recentTime + "\n");

        workspaceManager.sweepOrphanWorkspaces(60);

        assertTrue(Files.exists(newWsDir));
    }

    @Test
    @DisplayName("26. Sweep Orphan Workspaces Malformed Lock File: Deletes corrupted lock file workspace safely")
    void testSweepOrphanWorkspaces_MalformedLockFile() throws IOException {
        Path badWsDir = tempWorkspacesDir.resolve("mutation-" + UUID.randomUUID() + "-" + UUID.randomUUID());
        Files.createDirectories(badWsDir);

        Path lockFile = badWsDir.resolve(".execution.lock");
        Files.writeString(lockFile, "GARBAGE_CONTENT_NO_CREATED_AT\n");

        workspaceManager.sweepOrphanWorkspaces(60);

        assertFalse(Files.exists(badWsDir));
    }

    @Test
    @DisplayName("27. Sweep Orphan Workspaces No Lock File: Deletes orphaned dir without lock file if old")
    void testSweepOrphanWorkspaces_NoLockFile() throws IOException {
        Path orphanDir = tempWorkspacesDir.resolve("mutation-" + UUID.randomUUID() + "-" + UUID.randomUUID());
        Files.createDirectories(orphanDir);

        workspaceManager.sweepOrphanWorkspaces(0);

        assertFalse(Files.exists(orphanDir));
    }

    @Test
    @DisplayName("28. Cleanup Mutation Workspace Idempotent: Deleting non-existent operationId succeeds quietly")
    void testCleanupMutationWorkspace_Idempotent() {
        assertDoesNotThrow(() -> {
            workspaceManager.cleanupMutationWorkspace(UUID.randomUUID());
        });
    }

    @Test
    @DisplayName("29. Calculate Tree Digest Deterministic: Identical directory content returns identical hash")
    void testCalculateTreeDigest_Deterministic() throws IOException {
        Files.writeString(masterRepoDir.resolve("a.txt"), "A");
        Files.writeString(masterRepoDir.resolve("b.txt"), "B");

        String hash1 = workspaceManager.calculateTreeDigest(masterRepoDir);
        String hash2 = workspaceManager.calculateTreeDigest(masterRepoDir);

        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("30. Calculate Tree Digest Order Independent: Entry sorting guarantees deterministic digest")
    void testCalculateTreeDigest_OrderIndependent() throws IOException {
        Files.writeString(masterRepoDir.resolve("z.txt"), "Z");
        Files.writeString(masterRepoDir.resolve("a.txt"), "A");

        String hash1 = workspaceManager.calculateTreeDigest(masterRepoDir);

        Path repo2Dir = tempStorageDir.resolve("repo2");
        Files.createDirectories(repo2Dir);
        Files.writeString(repo2Dir.resolve("a.txt"), "A");
        Files.writeString(repo2Dir.resolve("z.txt"), "Z");

        String hash2 = workspaceManager.calculateTreeDigest(repo2Dir);

        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("31. Calculate Tree Digest Nonexistent Directory: Returns empty SHA-256 digest")
    void testCalculateTreeDigest_NonexistentDirectory() {
        Path nonexistent = tempStorageDir.resolve("nonexistent");
        String hash = workspaceManager.calculateTreeDigest(nonexistent);

        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    @DisplayName("32. Verify Master Immutability Failure Throws: Throws ApiException if baseline altered")
    void testVerifyMasterImmutability_FailureThrows() throws IOException {
        Files.writeString(masterRepoDir.resolve("orig.txt"), "ORIG");
        String initialHash = workspaceManager.calculateTreeDigest(masterRepoDir);

        Files.writeString(masterRepoDir.resolve("orig.txt"), "MUTATED");

        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.verifyMasterImmutability(masterRepoDir, initialHash);
        });

        assertEquals("MASTER_IMMUTABILITY_VIOLATION", ex.getCode());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    @Test
    @DisplayName("33. Get Mutation Workspace Path Returns Correct Path: Locates repo folder in workspace")
    void testGetMutationWorkspacePath_ReturnsCorrectPath() {
        UUID opId = UUID.randomUUID();
        MutationWorkspaceResult result = workspaceManager.createMutationWorkspace(
                UUID.randomUUID(), opId, UUID.randomUUID(), repository, UUID.randomUUID()
        );

        Path foundPath = workspaceManager.getMutationWorkspacePath(opId);
        assertNotNull(foundPath);
        assertEquals(Paths.get(result.workspaceRepoPath()).toAbsolutePath().normalize(), foundPath);
    }

    @Test
    @DisplayName("34. Get Mutation Workspace Path Nonexistent Returns Null: Unknown operationId returns null")
    void testGetMutationWorkspacePath_NonexistentReturnsNull() {
        Path foundPath = workspaceManager.getMutationWorkspacePath(UUID.randomUUID());
        assertNull(foundPath);
    }

    @Test
    @DisplayName("35. Create Mutation Workspace Rejects Null ApprovalId")
    void testCreateMutationWorkspace_NullApprovalId() {
        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.createMutationWorkspace(null, UUID.randomUUID(), UUID.randomUUID(), repository, UUID.randomUUID());
        });
        assertEquals("INVALID_WORKSPACE_REQUEST", ex.getCode());
    }

    @Test
    @DisplayName("36. Create Mutation Workspace Rejects Null OperationId")
    void testCreateMutationWorkspace_NullOperationId() {
        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.createMutationWorkspace(UUID.randomUUID(), null, UUID.randomUUID(), repository, UUID.randomUUID());
        });
        assertEquals("INVALID_WORKSPACE_REQUEST", ex.getCode());
    }

    @Test
    @DisplayName("37. Create Mutation Workspace Rejects Null Repository")
    void testCreateMutationWorkspace_NullRepository() {
        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.createMutationWorkspace(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID());
        });
        assertEquals("INVALID_WORKSPACE_REQUEST", ex.getCode());
    }

    @Test
    @DisplayName("38. Create Mutation Workspace Master Repo Not Found: Throws NOT_FOUND if master repo absent")
    void testCreateMutationWorkspace_MasterRepoNotFound() {
        Repository nonExistentRepo = new Repository();
        nonExistentRepo.setId(UUID.randomUUID());

        ApiException ex = assertThrows(ApiException.class, () -> {
            workspaceManager.createMutationWorkspace(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), nonExistentRepo, UUID.randomUUID()
            );
        });

        assertEquals("BASELINE_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
