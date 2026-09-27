package com.repopilot.testing;

import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.service.DockerSandboxExecutorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyRootIntegrationTest {

    @TempDir
    Path tempDir;

    private DockerSandboxExecutorServiceImpl executorService;

    @BeforeEach
    void setUp() {
        executorService = new DockerSandboxExecutorServiceImpl();
    }

    @Test
    void executeSandboxTest_readOnlyRootFlagEnforced() throws IOException {
        Path workspaceDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceDir);

        DockerExecutionResult result = executorService.executeSandboxTest(
                UUID.randomUUID(), UUID.randomUUID(), Ecosystem.PYTHON, null, workspaceDir, Duration.ofSeconds(10));

        assertNotNull(result);
        assertTrue(result.getCommandExecuted().contains("--read-only"));
        assertTrue(result.getCommandExecuted().contains("destination=/tmp"));
    }
}
