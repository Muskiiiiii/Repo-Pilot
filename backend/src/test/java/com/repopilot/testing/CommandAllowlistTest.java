package com.repopilot.testing;

import com.repopilot.common.exception.ApiException;
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

class CommandAllowlistTest {

    @TempDir
    Path tempDir;

    private DockerSandboxExecutorServiceImpl executorService;

    @BeforeEach
    void setUp() {
        executorService = new DockerSandboxExecutorServiceImpl();
    }

    @Test
    void executeSandboxTest_commandInjectionInTestTarget_throwsApiException() throws IOException {
        Path workspaceDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceDir);

        String maliciousTarget = "test; rm -rf / ;";

        ApiException exception = assertThrows(ApiException.class, () ->
                executorService.executeSandboxTest(
                        UUID.randomUUID(), UUID.randomUUID(), Ecosystem.PYTHON, maliciousTarget, workspaceDir, Duration.ofSeconds(5)));

        assertEquals("INVALID_TEST_TARGET", exception.getCode());
    }

    @Test
    void executeSandboxTest_pathTraversalInTestTarget_throwsApiException() throws IOException {
        Path workspaceDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceDir);

        String traversalTarget = "../../../etc/passwd";

        ApiException exception = assertThrows(ApiException.class, () ->
                executorService.executeSandboxTest(
                        UUID.randomUUID(), UUID.randomUUID(), Ecosystem.PYTHON, traversalTarget, workspaceDir, Duration.ofSeconds(5)));

        assertEquals("PATH_TRAVERSAL", exception.getCode());
    }
}
