package com.repopilot.testing;

import com.repopilot.repository.entity.Repository;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceIsolationTest {

    @TempDir
    Path tempDir;

    private WorkspaceManagerServiceImpl workspaceManagerService;
    private Path masterRepoDir;
    private Repository repository;
    private UUID executionId;

    @BeforeEach
    void setUp() throws IOException {
        workspaceManagerService = new WorkspaceManagerServiceImpl();
        masterRepoDir = tempDir.resolve("master_repo");
        Path workspaceBaseDir = tempDir.resolve("workspaces");

        Files.createDirectories(masterRepoDir);
        Files.createDirectories(workspaceBaseDir);

        ReflectionTestUtils.setField(workspaceManagerService, "baseStoragePath", tempDir.toString());
        ReflectionTestUtils.setField(workspaceManagerService, "workspaceBasePath", workspaceBaseDir.toString());

        UUID repoId = UUID.randomUUID();
        masterRepoDir = tempDir.resolve(repoId.toString());
        Files.createDirectories(masterRepoDir);

        Files.writeString(masterRepoDir.resolve("App.java"), "public class App { public static void main(String[] args) {} }");
        Files.writeString(masterRepoDir.resolve("README.md"), "# Master Repo");

        repository = new Repository();
        repository.setId(repoId);

        executionId = UUID.randomUUID();
    }

    @Test
    void materializeWorkspace_modifyingWorkspace_doesNotModifyMaster() throws IOException {
        String masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        Path workspaceRepoDir = workspaceManagerService.materializeWorkspace(executionId, repository);

        // Mutate file inside workspace
        Path wspFile = workspaceRepoDir.resolve("App.java");
        Files.writeString(wspFile, "// MUTATED WORKSPACE FILE CONTENT\npublic class App {}");

        String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        assertEquals(masterDigestBefore, masterDigestAfter, "Master repository tree digest MUST remain identical after workspace modification!");
        assertNotEquals(Files.readString(masterRepoDir.resolve("App.java")), Files.readString(wspFile));
    }
}
