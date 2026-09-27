package com.repopilot.testing.daemon.runtime;

import com.repopilot.testing.dto.DockerExecutionResult;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SandboxDockerRuntimeService {

    Path ensureSeccompProfile();

    Path prepareWorkspace(UUID jobId, String executionId, Path sourceRepoDir);

    List<String> buildSecureDockerCommand(UUID jobId, String executionId, UUID workerNodeId, Long fencingVersion,
                                           String containerName, String ecosystemOrImage, String commandType,
                                           List<String> commandArgsList, Path workspaceDir);

    DockerExecutionResult executeSecureContainer(UUID jobId, String executionId, UUID workerNodeId, Long fencingVersion,
                                                 String containerName, String ecosystemOrImage, String commandType,
                                                 List<String> commandArgsList, Path workspaceDir, Integer timeoutSeconds);

    boolean inspectContainer(String containerName);

    Map<String, String> inspectContainerConfig(String containerName);

    void terminateContainer(String containerName);

    void cleanupWorkspace(Path workspaceDir);

    List<Map<String, String>> discoverRepoPilotContainers();
}
