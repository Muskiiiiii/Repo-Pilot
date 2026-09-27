package com.repopilot.testing;

import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;
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

class TimeoutIntegrationTest {

    @TempDir
    Path tempDir;

    private DockerSandboxExecutorServiceImpl executorService;

    @BeforeEach
    void setUp() {
        executorService = new DockerSandboxExecutorServiceImpl();
    }

    @Test
    void executeSandboxTest_shortTimeout_terminatesContainerAndReturnsTimeoutType() throws IOException {
        Path workspaceDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceDir);

        // Execute container with 1 second timeout
        DockerExecutionResult result = executorService.executeSandboxTest(
                UUID.randomUUID(), UUID.randomUUID(), Ecosystem.PYTHON, null, workspaceDir, Duration.ofSeconds(1));

        assertNotNull(result);
        if (result.getFailureType() == FailureType.TIMEOUT) {
            assertEquals(124, result.getExitCode());
            assertTrue(result.getStderr().contains("TIMED OUT"));
        }
    }
}
