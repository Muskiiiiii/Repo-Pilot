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

class PathTraversalTest {

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
    void applyCodeChange_pathTraversalDotDot_throwsApiException() {
        CodeChange change = new CodeChange();
        change.setFilePath("../outside.txt");
        change.setOperation("CREATE");
        change.setNewContent("MALICIOUS");

        ApiException exception = assertThrows(ApiException.class, () ->
                workspaceManagerService.applyCodeChangeSnapshot(UUID.randomUUID(), workspaceRepoDir, List.of(change)));

        assertEquals("PATH_TRAVERSAL", exception.getCode());
    }

    @Test
    void applyCodeChange_encodedPathTraversal_throwsApiException() {
        CodeChange change = new CodeChange();
        change.setFilePath("sub/../../outside.txt");
        change.setOperation("CREATE");
        change.setNewContent("MALICIOUS");

        ApiException exception = assertThrows(ApiException.class, () ->
                workspaceManagerService.applyCodeChangeSnapshot(UUID.randomUUID(), workspaceRepoDir, List.of(change)));

        assertEquals("PATH_TRAVERSAL", exception.getCode());
    }
}
