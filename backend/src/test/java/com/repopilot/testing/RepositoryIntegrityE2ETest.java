package com.repopilot.testing;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.repository.entity.Repository;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryIntegrityE2ETest {

    @TempDir
    Path tempDir;

    private WorkspaceManagerServiceImpl workspaceManagerService;
    private Path masterRepoDir;
    private Repository repository;
    private UUID executionId;

    @BeforeEach
    void setUp() throws IOException {
        workspaceManagerService = new WorkspaceManagerServiceImpl();
        Path workspaceBaseDir = tempDir.resolve("workspaces");

        UUID repoId = UUID.randomUUID();
        masterRepoDir = tempDir.resolve(repoId.toString());
        Files.createDirectories(masterRepoDir);
        Files.createDirectories(workspaceBaseDir);

        ReflectionTestUtils.setField(workspaceManagerService, "baseStoragePath", tempDir.toString());
        ReflectionTestUtils.setField(workspaceManagerService, "workspaceBasePath", workspaceBaseDir.toString());

        // Create synthetic master repository files
        Files.writeString(masterRepoDir.resolve("Main.java"), "public class Main { public static void main(String[] args) {} }");
        Files.writeString(masterRepoDir.resolve("README.md"), "# Synthetic Master Repository");

        repository = new Repository();
        repository.setId(repoId);
        executionId = UUID.randomUUID();
    }

    @Test
    void executePipeline_masterRepositoryByteForByteUnchanged() throws IOException {
        // 1. Calculate Master Repository Digest BEFORE
        String masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        assertNotNull(masterDigestBefore);

        // 2. Materialize Workspace & Apply Changes
        Path workspaceRepoDir = workspaceManagerService.materializeWorkspace(executionId, repository);

        CodeChange change = new CodeChange();
        change.setFilePath("Main.java");
        change.setOperation("MODIFY");
        change.setNewContent("public class Main { public static void main(String[] args) { System.out.println(\"Updated\"); } }");
        workspaceManagerService.applyCodeChangeSnapshot(executionId, workspaceRepoDir, List.of(change));

        // 3. Cleanup Workspace
        workspaceManagerService.cleanupWorkspace(executionId);

        // 4. Calculate Master Repository Digest AFTER
        String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        // 5. Assert Master Integrity
        assertEquals(masterDigestBefore, masterDigestAfter, "Master repository tree digest MUST be byte-for-byte identical after execution pipeline!");
    }
}
