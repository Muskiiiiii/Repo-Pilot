package com.repopilot.testing.service;

import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.entity.Ecosystem;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

public interface DockerSandboxExecutorService {

    DockerExecutionResult executeSandboxTest(UUID executionId, UUID taskId, Ecosystem ecosystem, String testTarget, Path workspaceRepoDir, Duration timeout);

    void terminateContainer(String containerId);

    void cleanupContainersByExecutionId(UUID executionId);

    void cleanupOrphanContainers();
}
