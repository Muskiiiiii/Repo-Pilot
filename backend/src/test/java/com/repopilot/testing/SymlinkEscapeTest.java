package com.repopilot.testing;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.common.exception.ApiException;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SymlinkEscapeTest {

    @TempDir
    Path tempDir;

    private WorkspaceManagerServiceImpl workspaceManagerService;
    private Path workspaceRepoDir;

    @BeforeEach
    void setUp() throws IOException {
        workspaceManagerService = new WorkspaceManagerServiceImpl();
        workspaceRepoDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceRepoDir);
    }

    @Test
    void applyCodeChange_symlinkTargetingOutsideWorkspace_throwsApiException() throws IOException {
        Path outsideFile = tempDir.resolve("outside_secret.txt");
        Files.writeString(outsideFile, "SECRET");

        Path symlinkPath = workspaceRepoDir.resolve("symlink_to_outside.txt");
        try {
            Files.createSymbolicLink(symlinkPath, outsideFile);
        } catch (UnsupportedOperationException e) {
            // Symlinks not supported on OS/filesystem environment
            return;
        }

        CodeChange change = new CodeChange();
        change.setFilePath("symlink_to_outside.txt");
        change.setOperation("MODIFY");
        change.setNewContent("OVERWRITE_SECRET");

        ApiException exception = assertThrows(ApiException.class, () ->
                workspaceManagerService.applyCodeChangeSnapshot(UUID.randomUUID(), workspaceRepoDir, List.of(change)));

        assertTrue(exception.getCode().contains("SYMLINK") || exception.getCode().contains("TRAVERSAL"));
    }
}
