package com.repopilot.testing;

import com.repopilot.repository.entity.Repository;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CleanupIntegrationTest {

    @TempDir
    Path tempDir;

    private WorkspaceManagerServiceImpl workspaceManagerService;
    private Repository repository;
    private UUID executionId;

    @BeforeEach
    void setUp() throws IOException {
        workspaceManagerService = new WorkspaceManagerServiceImpl();
        Path workspaceBaseDir = tempDir.resolve("workspaces");

        UUID repoId = UUID.randomUUID();
        Path masterRepoDir = tempDir.resolve(repoId.toString());
        Files.createDirectories(masterRepoDir);
        Files.createDirectories(workspaceBaseDir);

        ReflectionTestUtils.setField(workspaceManagerService, "baseStoragePath", tempDir.toString());
        ReflectionTestUtils.setField(workspaceManagerService, "workspaceBasePath", workspaceBaseDir.toString());

        Files.writeString(masterRepoDir.resolve("App.java"), "public class App {}");

        repository = new Repository();
        repository.setId(repoId);
        executionId = UUID.randomUUID();
    }

    @Test
    void cleanupWorkspace_deletesWorkspaceDirectoryRecursively() {
        workspaceManagerService.materializeWorkspace(executionId, repository);
        Path workspaceRepoDir = workspaceManagerService.getWorkspaceRepoDir(executionId);

        assertTrue(Files.exists(workspaceRepoDir), "Workspace directory must exist after materialization");

        workspaceManagerService.cleanupWorkspace(executionId);

        Path workspaceRootDir = workspaceRepoDir.getParent();
        assertFalse(Files.exists(workspaceRootDir), "Workspace root directory MUST be completely deleted after cleanup!");
    }
}
