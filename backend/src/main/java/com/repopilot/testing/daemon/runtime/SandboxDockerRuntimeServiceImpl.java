package com.repopilot.testing.daemon.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.TerminationReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Service
public class SandboxDockerRuntimeServiceImpl implements SandboxDockerRuntimeService {

    private static final Logger logger = LoggerFactory.getLogger(SandboxDockerRuntimeServiceImpl.class);

    private final SandboxDockerConfig config = new SandboxDockerConfig();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${repopilot.testing.docker-binary:docker}")
    private String dockerBinary = SandboxDockerConfig.DEFAULT_DOCKER_BINARY;

    @Override
    public Path ensureSeccompProfile() {
        try {
            if (config.getSeccompProfilePath() != null) {
                Path custom = Path.of(config.getSeccompProfilePath());
                if (!Files.exists(custom)) {
                    throw new SecurityException("Configured custom seccomp profile does not exist: " + custom);
                }
                return custom.toAbsolutePath().normalize();
            }

            Path seccompDir = Path.of("/tmp", "repopilot-seccomp").toAbsolutePath().normalize();
            if (!Files.exists(seccompDir)) {
                Files.createDirectories(seccompDir);
            }

            Path targetFile = seccompDir.resolve("seccomp_unprivileged_strict.json");

            try (InputStream in = getClass().getClassLoader().getResourceAsStream(SandboxDockerConfig.SECCOMP_PROFILE_RESOURCE_PATH)) {
                if (in == null) {
                    if (Files.exists(targetFile)) {
                        return targetFile;
                    }
                    throw new SecurityException("Seccomp profile resource " + SandboxDockerConfig.SECCOMP_PROFILE_RESOURCE_PATH + " not found on classpath: FAIL CLOSED");
                }
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!Files.exists(targetFile) || Files.size(targetFile) == 0) {
                throw new SecurityException("Seccomp profile target file is empty or missing: FAIL CLOSED");
            }

            return targetFile;
        } catch (IOException e) {
            throw new SecurityException("Failed to ensure Seccomp profile: FAIL CLOSED - " + e.getMessage(), e);
        }
    }

    @Override
    public Path prepareWorkspace(UUID jobId, String executionId, Path sourceRepoDir) {
        if (executionId == null || executionId.contains("..") || executionId.contains("/") || executionId.contains("\\")) {
            throw new SecurityException("Path traversal detected in executionId: " + executionId);
        }

        try {
            Path tmpParent = Path.of("/tmp", "repopilot-workspaces").toAbsolutePath().normalize();
            if (Files.exists(tmpParent)) {
                tmpParent = tmpParent.toRealPath();
            } else {
                Files.createDirectories(tmpParent);
                tmpParent = tmpParent.toRealPath();
            }

            Path workspaceDir = tmpParent.resolve("exec_" + executionId).toAbsolutePath().normalize();
            if (!workspaceDir.startsWith(tmpParent)) {
                throw new SecurityException("Workspace path traversal detected outside allowed directory: " + workspaceDir);
            }

            if (sourceRepoDir != null && Files.exists(sourceRepoDir)) {
                Path realSource = sourceRepoDir.toRealPath();
                if (realSource.toString().startsWith("/etc") || realSource.toString().startsWith("/proc") || realSource.toString().startsWith("/sys")) {
                    throw new SecurityException("Forbidden source repository directory: " + realSource);
                }
            }

            if (Files.exists(workspaceDir)) {
                cleanupWorkspace(workspaceDir);
            }
            Files.createDirectories(workspaceDir);

            if (sourceRepoDir != null && Files.exists(sourceRepoDir)) {
                Path realSource = sourceRepoDir.toRealPath();
                copyDirectoryRecursively(realSource, workspaceDir);
            }

            return workspaceDir;
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare workspace for execution " + executionId + ": " + e.getMessage(), e);
        }
    }

    private void copyDirectoryRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        if (!Files.exists(dest)) {
                            Files.createDirectories(dest);
                        }
                    } else if (Files.isRegularFile(src) && !Files.isSymbolicLink(src)) {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Error copying file: " + src, e);
                }
            });
        }
    }

    @Override
    public List<String> buildSecureDockerCommand(UUID jobId, String executionId, UUID workerNodeId, Long fencingVersion,
                                                  String containerName, String ecosystemOrImage, String commandType,
                                                  List<String> commandArgsList, Path workspaceDir) {
        // Enforce Seccomp Profile Fail-Closed check
        Path seccompProfile = ensureSeccompProfile();

        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--name");
        cmd.add(containerName);

        // Security Policy Enforcement (Server-Side)
        cmd.add("--label");
        cmd.add("repopilot.job_id=" + jobId);
        cmd.add("--label");
        cmd.add("repopilot.execution_id=" + executionId);
        cmd.add("--label");
        cmd.add("repopilot.worker_id=" + workerNodeId);
        cmd.add("--label");
        cmd.add("repopilot.fencing_version=" + fencingVersion);

        cmd.add("--network");
        cmd.add(SandboxDockerConfig.NETWORK_NONE);

        cmd.add("--user");
        cmd.add(config.getUserGid());

        cmd.add("--read-only");
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges:true");
        cmd.add("--security-opt=seccomp=" + seccompProfile.toString());

        cmd.add("--cpus");
        cmd.add(String.valueOf(config.getCpuLimitCores()));

        cmd.add("--memory");
        cmd.add(config.getMemoryLimitMb() + "m");

        cmd.add("--memory-swap");
        cmd.add(config.getMemoryLimitMb() + "m"); // 0 swap

        cmd.add("--pids-limit");
        cmd.add(String.valueOf(config.getPidLimit()));

        cmd.add("--mount");
        cmd.add("type=tmpfs,destination=/tmp,tmpfs-size=512m,tmpfs-mode=1777");

        if (workspaceDir != null) {
            cmd.add("--mount");
            cmd.add("type=bind,source=" + workspaceDir.toAbsolutePath() + ",target=/workspace/repo,readonly=false");
        }

        // Image Selection (Server-Side Policy)
        String image = resolveApprovedImage(ecosystemOrImage);
        cmd.add(image);

        // Structured Command Arguments (No shell interpolation)
        if (commandArgsList != null && !commandArgsList.isEmpty()) {
            cmd.addAll(commandArgsList);
        }

        return cmd;
    }

    private String resolveApprovedImage(String ecosystemOrImage) {
        if (ecosystemOrImage == null) return "python:3.11-slim";
        String normalized = ecosystemOrImage.trim().toLowerCase();
        if (normalized.contains("maven") || normalized.contains("java")) {
            return "maven:3.9-eclipse-temurin-17-alpine";
        } else if (normalized.contains("go") || normalized.contains("golang")) {
            return "golang:1.22-alpine";
        } else if (normalized.contains("node") || normalized.contains("npm")) {
            return "node:20-alpine";
        }
        return "python:3.11-slim";
    }

    @Override
    public DockerExecutionResult executeSecureContainer(UUID jobId, String executionId, UUID workerNodeId, Long fencingVersion,
                                                        String containerName, String ecosystemOrImage, String commandType,
                                                        List<String> commandArgsList, Path workspaceDir, Integer timeoutSeconds) {
        List<String> cmd = buildSecureDockerCommand(jobId, executionId, workerNodeId, fencingVersion, containerName, ecosystemOrImage, commandType, commandArgsList, workspaceDir);
        String fullCmd = String.join(" ", cmd);

        int effectiveTimeout = (timeoutSeconds != null && timeoutSeconds > 0 && timeoutSeconds <= 120) ? timeoutSeconds : config.getTimeoutSeconds();

        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear(); // Clean environment isolation

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            logger.warn("Docker process launch returned failure/mock mode for execution {}: {}", executionId, e.getMessage());
            return new DockerExecutionResult(127, "", "Docker process not launched: " + e.getMessage(),
                    0L, containerName, FailureType.EXECUTION_INFRA_FAILURE, false, false, fullCmd,
                    TerminationReason.EXECUTION_INFRA_FAILURE, 0L, 0L);
        }

        ExecutorService streamExecutor = Executors.newFixedThreadPool(2);
        Future<OutputBuffer> stdoutFuture = streamExecutor.submit(() -> readBoundedStream(process.getInputStream()));
        Future<OutputBuffer> stderrFuture = streamExecutor.submit(() -> readBoundedStream(process.getErrorStream()));

        boolean finished = false;
        try {
            finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long durationMs = Duration.between(start, Instant.now()).toMillis();
        OutputBuffer stdoutBuf = getOutput(stdoutFuture);
        OutputBuffer stderrBuf = getOutput(stderrFuture);
        streamExecutor.shutdownNow();

        if (!finished) {
            terminateContainer(containerName);
            process.destroyForcibly();
            return new DockerExecutionResult(124, stdoutBuf.content, stderrBuf.content + "\n[CONTAINER TIMED OUT AFTER " + effectiveTimeout + "s]",
                    durationMs, containerName, FailureType.TIMEOUT, stdoutBuf.truncated, true, fullCmd,
                    TerminationReason.TIMEOUT, stdoutBuf.bytesObserved, stderrBuf.bytesObserved);
        }

        int exitCode = process.exitValue();
        FailureType failureType = exitCode == 0 ? FailureType.PASS : FailureType.TEST_FAILURE;
        TerminationReason terminationReason = exitCode == 0 ? TerminationReason.NORMAL_EXIT : TerminationReason.NORMAL_EXIT;

        return new DockerExecutionResult(exitCode, stdoutBuf.content, stderrBuf.content, durationMs, containerName,
                failureType, stdoutBuf.truncated, stderrBuf.truncated, fullCmd, terminationReason,
                stdoutBuf.bytesObserved, stderrBuf.bytesObserved);
    }

    private static class OutputBuffer {
        final String content;
        final boolean truncated;
        final long bytesObserved;

        OutputBuffer(String content, boolean truncated, long bytesObserved) {
            this.content = content;
            this.truncated = truncated;
            this.bytesObserved = bytesObserved;
        }
    }

    private OutputBuffer readBoundedStream(InputStream stream) {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        long totalBytes = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                totalBytes += n;
                if (sb.length() < config.getMaxOutputChars()) {
                    int toAppend = Math.min(n, config.getMaxOutputChars() - sb.length());
                    sb.append(buffer, 0, toAppend);
                    if (sb.length() >= config.getMaxOutputChars() && n > toAppend) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
                // Cap stream iteration at 10MB total read bytes to prevent stream memory/CPU burn
                if (totalBytes > 10_000_000L) {
                    truncated = true;
                    break;
                }
            }
        } catch (IOException ignored) {
        }
        return new OutputBuffer(sb.toString(), truncated, totalBytes);
    }

    private OutputBuffer getOutput(Future<OutputBuffer> future) {
        try {
            return future.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new OutputBuffer("", false, 0L);
        }
    }

    @Override
    public boolean inspectContainer(String containerName) {
        if (containerName == null || containerName.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerBinary, "inspect", containerName);
            pb.environment().clear();
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, String> inspectContainerConfig(String containerName) {
        Map<String, String> result = new HashMap<>();
        if (containerName == null || containerName.isBlank()) return result;
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerBinary, "inspect", containerName);
            pb.environment().clear();
            Process p = pb.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(baos);
            }
            p.waitFor(3, TimeUnit.SECONDS);
            p.destroyForcibly();

            String jsonStr = baos.toString(StandardCharsets.UTF_8);
            if (!jsonStr.isBlank()) {
                JsonNode root = objectMapper.readTree(jsonStr);
                if (root.isArray() && root.size() > 0) {
                    JsonNode containerNode = root.get(0);
                    JsonNode hostConfig = containerNode.path("HostConfig");
                    JsonNode configNode = containerNode.path("Config");

                    result.put("NetworkMode", hostConfig.path("NetworkMode").asText(""));
                    result.put("User", configNode.path("User").asText(""));
                    result.put("ReadonlyRootfs", String.valueOf(hostConfig.path("ReadonlyRootfs").asBoolean(false)));
                    result.put("CapDrop", hostConfig.path("CapDrop").toString());
                    result.put("SecurityOpt", hostConfig.path("SecurityOpt").toString());
                    result.put("Memory", String.valueOf(hostConfig.path("Memory").asLong(0)));
                    result.put("MemorySwap", String.valueOf(hostConfig.path("MemorySwap").asLong(0)));
                    result.put("NanoCpus", String.valueOf(hostConfig.path("NanoCpus").asLong(0)));
                    result.put("PidsLimit", String.valueOf(hostConfig.path("PidsLimit").asLong(0)));
                    result.put("Labels", configNode.path("Labels").toString());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to inspect container {}: {}", containerName, e.getMessage());
        }
        return result;
    }

    @Override
    public void terminateContainer(String containerName) {
        if (containerName == null || containerName.isBlank()) return;
        try {
            ProcessBuilder pbStop = new ProcessBuilder(dockerBinary, "stop", "-t", "2", containerName);
            pbStop.environment().clear();
            Process pStop = pbStop.start();
            pStop.waitFor(3, TimeUnit.SECONDS);
            pStop.destroyForcibly();

            ProcessBuilder pbRm = new ProcessBuilder(dockerBinary, "rm", "-f", containerName);
            pbRm.environment().clear();
            Process pRm = pbRm.start();
            pRm.waitFor(2, TimeUnit.SECONDS);
            pRm.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void cleanupWorkspace(Path workspaceDir) {
        if (workspaceDir == null || !Files.exists(workspaceDir)) return;
        try {
            Path canonical = workspaceDir.toAbsolutePath().normalize();
            if (Files.exists(canonical)) {
                canonical = canonical.toRealPath();
            }

            Path root1 = Path.of("/tmp", "repopilot-workspaces").toAbsolutePath().normalize();
            if (Files.exists(root1)) {
                root1 = root1.toRealPath();
            }
            Path root2 = Path.of("/tmp", "repopilot-artifacts").toAbsolutePath().normalize();
            if (Files.exists(root2)) {
                root2 = root2.toRealPath();
            }

            if (!canonical.startsWith(root1) && !canonical.startsWith(root2) &&
                !canonical.toString().startsWith("/tmp/repopilot-workspaces") &&
                !canonical.toString().startsWith("/private/tmp/repopilot-workspaces") &&
                !canonical.toString().startsWith("/tmp/repopilot-artifacts") &&
                !canonical.toString().startsWith("/private/tmp/repopilot-artifacts")) {
                throw new SecurityException("Forbidden workspace cleanup path outside allowed root: " + canonical);
            }

            try (Stream<Path> stream = Files.walk(canonical)) {
                Path finalRoot1 = root1;
                Path finalRoot2 = root2;
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        if (Files.isSymbolicLink(p)) {
                            Files.deleteIfExists(p);
                        } else {
                            Path norm = p.toAbsolutePath().normalize();
                            if (norm.startsWith(finalRoot1) || norm.startsWith(finalRoot2) ||
                                norm.toString().startsWith("/tmp/repopilot-workspaces") ||
                                norm.toString().startsWith("/private/tmp/repopilot-workspaces")) {
                                Files.deleteIfExists(p);
                            }
                        }
                    } catch (IOException ignored) {}
                });
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ignored) {}
    }

    @Override
    public List<Map<String, String>> discoverRepoPilotContainers() {
        List<Map<String, String>> containers = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerBinary, "ps", "-a", "--filter", "label=repopilot.job_id", "--format", "{{.Names}}|{{.Labels}}");
            pb.environment().clear();
            Process p = pb.start();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(baos);
            }
            p.waitFor(3, TimeUnit.SECONDS);

            String output = baos.toString(StandardCharsets.UTF_8);
            if (!output.isBlank()) {
                for (String line : output.split("\n")) {
                    line = line.trim();
                    if (line.isBlank() || !line.contains("|")) continue;
                    String[] parts = line.split("\\|", 2);
                    String name = parts[0].trim();
                    String labelsStr = parts[1].trim();

                    Map<String, String> entry = new HashMap<>();
                    entry.put("name", name);

                    for (String labelPair : labelsStr.split(",")) {
                        String[] kv = labelPair.split("=", 2);
                        if (kv.length == 2) {
                            String k = kv[0].trim();
                            String v = kv[1].trim();
                            if (k.equals("repopilot.job_id")) entry.put("job_id", v);
                            else if (k.equals("repopilot.execution_id")) entry.put("execution_id", v);
                            else if (k.equals("repopilot.worker_id")) entry.put("worker_id", v);
                            else if (k.equals("repopilot.fencing_version")) entry.put("fencing_version", v);
                        }
                    }

                    if (entry.containsKey("job_id")) {
                        containers.add(entry);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error discovering RepoPilot containers: {}", e.getMessage());
        }
        return containers;
    }
}
