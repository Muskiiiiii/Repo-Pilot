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

class CodeChangeHashValidationTest {

    @TempDir
    Path tempDir;

    private WorkspaceManagerServiceImpl workspaceManagerService;
    private Path workspaceRepoDir;

    @BeforeEach
    void setUp() throws IOException {
        workspaceManagerService = new WorkspaceManagerServiceImpl();
        workspaceRepoDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceRepoDir);

        Files.writeString(workspaceRepoDir.resolve("App.java"), "public class App {}");
    }

    @Test
    void applyCodeChange_originalHashMismatch_throwsApiException() {
        CodeChange change = new CodeChange();
        change.setFilePath("App.java");
        change.setOperation("MODIFY");
        change.setOriginalHash("0000000000000000000000000000000000000000000000000000000000000000"); // Mismatched SHA-256
        change.setProposedHash("1111111111111111111111111111111111111111111111111111111111111111");
        change.setNewContent("public class App { int x; }");

        ApiException exception = assertThrows(ApiException.class, () ->
                workspaceManagerService.applyCodeChangeSnapshot(UUID.randomUUID(), workspaceRepoDir, List.of(change)));

        assertEquals("HASH_MISMATCH", exception.getCode());
    }
}
