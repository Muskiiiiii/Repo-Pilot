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

class SecretIsolationTest {

    @TempDir
    Path tempDir;

    private DockerSandboxExecutorServiceImpl executorService;

    @BeforeEach
    void setUp() {
        executorService = new DockerSandboxExecutorServiceImpl();
    }

    @Test
    void executeSandboxTest_containerEnvDoesNotContainHostSecrets() throws IOException {
        Path workspaceDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceDir);

        DockerExecutionResult result = executorService.executeSandboxTest(
                UUID.randomUUID(), UUID.randomUUID(), Ecosystem.PYTHON, null, workspaceDir, Duration.ofSeconds(10));

        assertNotNull(result);
        String stdout = result.getStdout();
        assertFalse(stdout.contains("GITHUB_TOKEN"), "Container environment MUST NOT contain GITHUB_TOKEN");
        assertFalse(stdout.contains("POSTGRES_PASSWORD"), "Container environment MUST NOT contain POSTGRES_PASSWORD");
        assertFalse(stdout.contains("LLM_API_KEY"), "Container environment MUST NOT contain LLM_API_KEY");
        assertFalse(stdout.contains("VOYAGE_API_KEY"), "Container environment MUST NOT contain VOYAGE_API_KEY");
    }
}
