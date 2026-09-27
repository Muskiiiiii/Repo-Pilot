package com.repopilot.testing;

import com.repopilot.repository.entity.Repository;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HardlinkRejectionTest {

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

        Files.writeString(masterRepoDir.resolve("TestFile.txt"), "INODE_TEST_CONTENT");

        repository = new Repository();
        repository.setId(repoId);
        executionId = UUID.randomUUID();
    }

    @Test
    void materializeWorkspace_verifiesDistinctInodes_noHardlinksUsed() throws IOException {
        Path workspaceRepoDir = workspaceManagerService.materializeWorkspace(executionId, repository);

        Path masterFile = masterRepoDir.resolve("TestFile.txt");
        Path workspaceFile = workspaceRepoDir.resolve("TestFile.txt");

        Object masterInode = Files.getAttribute(masterFile, "unix:ino");
        Object workspaceInode = Files.getAttribute(workspaceFile, "unix:ino");

        if (masterInode != null && workspaceInode != null) {
            assertNotEquals(masterInode, workspaceInode, "Workspace file MUST NOT share a hardlink inode with master repository file!");
        } else {
            // Fallback for non-POSIX filesystem attribute check
            Files.writeString(workspaceFile, "MUTATED_CONTENT");
            assertNotEquals(Files.readString(masterFile), Files.readString(workspaceFile));
        }
    }
}
