package com.repopilot.testing;

import com.repopilot.common.exception.ApiException;
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

class DockerPolicyTest {

    @TempDir
    Path tempDir;

    private DockerSandboxExecutorServiceImpl executorService;

    @BeforeEach
    void setUp() {
        executorService = new DockerSandboxExecutorServiceImpl();
    }

    @Test
    void executeSandboxTest_commandConstructedWithServerSideSecurityFlags() throws IOException {
        Path workspaceDir = tempDir.resolve("repo");
        Files.createDirectories(workspaceDir);

        DockerExecutionResult result = executorService.executeSandboxTest(
                UUID.randomUUID(), UUID.randomUUID(), Ecosystem.PYTHON, null, workspaceDir, Duration.ofSeconds(5));

        assertNotNull(result);
        String cmd = result.getCommandExecuted();
        assertTrue(cmd.contains("--network none"), "Command MUST contain --network none");
        assertTrue(cmd.contains("--user 65534:65534"), "Command MUST enforce non-root user 65534");
        assertTrue(cmd.contains("--read-only"), "Command MUST enforce read-only container root");
        assertTrue(cmd.contains("--cap-drop=ALL"), "Command MUST drop all Linux capabilities");
        assertTrue(cmd.contains("--security-opt=no-new-privileges:true"), "Command MUST enforce no-new-privileges");
        assertTrue(cmd.contains("--memory 512m"), "Command MUST enforce memory limit");
        assertTrue(cmd.contains("--cpus 1.5"), "Command MUST enforce CPU limit");
        assertTrue(cmd.contains("--pids-limit 100"), "Command MUST enforce PID limit");
    }
}
