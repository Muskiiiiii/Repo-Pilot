package com.repopilot.testing.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.TerminationReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class DockerSandboxExecutorServiceImpl implements DockerSandboxExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(DockerSandboxExecutorServiceImpl.class);
    private static final int MAX_OUTPUT_CHARS = 100000;

    @Value("${repopilot.testing.docker-binary:docker}")
    private String dockerBinary;

    @Override
    public DockerExecutionResult executeSandboxTest(UUID executionId, UUID taskId, Ecosystem ecosystem, String testTarget, Path workspaceRepoDir, Duration timeout) {
        if (ecosystem == null) {
            ecosystem = Ecosystem.PYTHON;
        }

        validateTestTargetSecurity(testTarget);

        String containerName = "repopilot_exec_" + executionId.toString();
        List<String> commandList = buildDockerCommand(executionId, taskId, containerName, ecosystem, testTarget, workspaceRepoDir);
        String fullCommandStr = String.join(" ", commandList);

        logger.info("Launching isolated sandbox container for execution {} (Ecosystem: {}, Command: {})", executionId, ecosystem, fullCommandStr);

        Instant start = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(commandList);
        pb.environment().clear();

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            logger.error("Failed to start Docker process for execution {}: {}", executionId, e.getMessage());
            return new DockerExecutionResult(127, "", "Failed to launch Docker executor process: " + e.getMessage(),
                    0L, containerName, FailureType.EXECUTION_INFRA_FAILURE, false, false, fullCommandStr,
                    TerminationReason.EXECUTION_INFRA_FAILURE, 0L, 0L);
        }

        ExecutorService streamExecutor = Executors.newFixedThreadPool(2);
        Future<OutputBuffer> stdoutFuture = streamExecutor.submit(() -> readStream(process.getInputStream()));
        Future<OutputBuffer> stderrFuture = streamExecutor.submit(() -> readStream(process.getErrorStream()));

        long timeoutMs = timeout != null ? timeout.toMillis() : 60000L;
        boolean finished = false;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Execution thread interrupted for execution {}", executionId);
        }

        long durationMs = Duration.between(start, Instant.now()).toMillis();

        OutputBuffer stdoutBuf = getFutureOutput(stdoutFuture);
        OutputBuffer stderrBuf = getFutureOutput(stderrFuture);
        streamExecutor.shutdownNow();

        if (!finished) {
            logger.warn("Sandbox container for execution {} timed out after {} ms. Force terminating.", executionId, timeoutMs);
            terminateContainer(containerName);
            process.destroyForcibly();

            return new DockerExecutionResult(124, stdoutBuf.content, stderrBuf.content + "\n[EXECUTION TIMED OUT AFTER " + timeoutMs + " ms]",
                    durationMs, containerName, FailureType.TIMEOUT, stdoutBuf.truncated, true, fullCommandStr,
                    TerminationReason.TIMEOUT, stdoutBuf.bytesObserved, stderrBuf.bytesObserved);
        }

        int exitCode = process.exitValue();
        FailureType failureType = classifyFailure(exitCode, stdoutBuf.content, stderrBuf.content);
        TerminationReason terminationReason = classifyTerminationReason(exitCode, failureType, stdoutBuf.content, stderrBuf.content);

        logger.info("Sandbox container for execution {} completed with exit code {} (FailureType: {}, Duration: {} ms)",
                executionId, exitCode, failureType, durationMs);

        return new DockerExecutionResult(exitCode, stdoutBuf.content, stderrBuf.content, durationMs,
                containerName, failureType, stdoutBuf.truncated, stderrBuf.truncated, fullCommandStr,
                terminationReason, stdoutBuf.bytesObserved, stderrBuf.bytesObserved);
    }

    @Override
    public void terminateContainer(String containerName) {
        if (containerName == null || containerName.isBlank()) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerBinary, "stop", "-t", "2", containerName);
            pb.environment().clear();
            Process p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);
            p.destroyForcibly();

            ProcessBuilder pbRm = new ProcessBuilder(dockerBinary, "rm", "-f", containerName);
            pbRm.environment().clear();
            Process pRm = pbRm.start();
            pRm.waitFor(2, TimeUnit.SECONDS);
            pRm.destroyForcibly();
            logger.info("Terminated container {}", containerName);
        } catch (Exception e) {
            logger.warn("Error terminating container {}: {}", containerName, e.getMessage());
        }
    }

    @Override
    public void cleanupOrphanContainers() {
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerBinary, "ps", "-a", "--filter", "label=repopilot.execution_id", "--format", "{{.ID}} {{.Names}}");
            pb.environment().clear();
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0 && !parts[0].isBlank()) {
                    logger.info("Cleaning up orphan container {}", parts[0]);
                    terminateContainer(parts[0]);
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Failed to query orphan containers: {}", e.getMessage());
        }
    }

    @Override
    public void cleanupContainersByExecutionId(UUID executionId) {
        if (executionId == null) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(dockerBinary, "ps", "-a", "--filter", "label=repopilot.execution_id=" + executionId, "--format", "{{.ID}}");
            pb.environment().clear();
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            List<String> containerIds = new ArrayList<>();
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isBlank()) {
                    containerIds.add(line.trim());
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);

            for (String cid : containerIds) {
                logger.info("Cleaning up container {} for execution {}", cid, executionId);
                terminateContainer(cid);
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup containers for execution {}: {}", executionId, e.getMessage());
        }
    }

    private List<String> buildDockerCommand(UUID executionId, UUID taskId, String containerName, Ecosystem ecosystem, String testTarget, Path workspaceRepoDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBinary);
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--label");
        cmd.add("repopilot.execution_id=" + executionId);
        cmd.add("--label");
        cmd.add("repopilot.task_execution_id=" + executionId);
        cmd.add("--label");
        cmd.add("repopilot.task_id=" + taskId);
        cmd.add("--label");
        cmd.add("repopilot.workspace_id=" + workspaceRepoDir.getFileName().toString());
        cmd.add("--network");
        cmd.add("none");
        cmd.add("--user");
        cmd.add("65534:65534");
        cmd.add("--read-only");
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges:true");
        cmd.add("--memory");
        cmd.add("512m");
        cmd.add("--cpus");
        cmd.add("1.5");
        cmd.add("--pids-limit");
        cmd.add("100");
        cmd.add("--mount");
        cmd.add("type=tmpfs,destination=/tmp,tmpfs-size=512m,tmpfs-mode=1777");
        cmd.add("--mount");
        cmd.add("type=bind,source=" + workspaceRepoDir.toAbsolutePath() + ",target=/workspace/repo,readonly=false");

        // Server-Side Image & Runner Selection Policy
        switch (ecosystem) {
            case MAVEN:
                cmd.add("maven:3.9.6-eclipse-temurin-21-alpine");
                cmd.add("mvn");
                cmd.add("test");
                cmd.add("-f");
                cmd.add("/workspace/repo/pom.xml");
                if (testTarget != null && !testTarget.isBlank()) {
                    cmd.add("-Dtest=" + testTarget);
                }
                break;
            case GRADLE:
                cmd.add("gradle:8.6-jdk21-alpine");
                cmd.add("gradle");
                cmd.add("test");
                cmd.add("-p");
                cmd.add("/workspace/repo");
                if (testTarget != null && !testTarget.isBlank()) {
                    cmd.add("--tests");
                    cmd.add(testTarget);
                }
                break;
            case NODE:
                cmd.add("node:20-slim");
                cmd.add("node");
                cmd.add("--test");
                cmd.add("/workspace/repo/" + (testTarget != null && !testTarget.isBlank() ? testTarget : ""));
                break;
            case GO:
                cmd.add("golang:1.22-alpine");
                cmd.add("go");
                cmd.add("test");
                cmd.add("/workspace/repo/" + (testTarget != null && !testTarget.isBlank() ? testTarget : "..."));
                break;
            case RUST:
                cmd.add("rust:1.76-alpine");
                cmd.add("cargo");
                cmd.add("test");
                cmd.add("--manifest-path");
                cmd.add("/workspace/repo/Cargo.toml");
                if (testTarget != null && !testTarget.isBlank()) {
                    cmd.add(testTarget);
                }
                break;
            case PYTHON:
            default:
                cmd.add("python:3.11-slim");
                cmd.add("python3");
                cmd.add("-m");
                cmd.add("unittest");
                if (testTarget != null && !testTarget.isBlank()) {
                    cmd.add("/workspace/repo/" + testTarget);
                } else {
                    cmd.add("discover");
                    cmd.add("-s");
                    cmd.add("/workspace/repo");
                }
                break;
        }

        return cmd;
    }

    private void validateTestTargetSecurity(String testTarget) {
        if (testTarget == null || testTarget.isBlank()) return;
        if (testTarget.startsWith("/") || testTarget.startsWith("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Test target cannot be an absolute path");
        }
        if (testTarget.contains("\0")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEST_TARGET", "Test target contains null byte");
        }
        if (!testTarget.matches("^[a-zA-Z0-9_/\\.-]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEST_TARGET", "Test target contains forbidden shell characters");
        }
        if (testTarget.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Test target contains forbidden '..' traversal sequence");
        }
    }

    private OutputBuffer readStream(java.io.InputStream is) {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        long bytesObserved = 0L;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                byte[] lineBytes = line.getBytes(StandardCharsets.UTF_8);
                bytesObserved += lineBytes.length + 1; // plus newline character
                if (sb.length() + line.length() <= MAX_OUTPUT_CHARS) {
                    sb.append(line).append("\n");
                } else {
                    truncated = true;
                    int allowed = MAX_OUTPUT_CHARS - sb.length();
                    if (allowed > 0) {
                        sb.append(line, 0, allowed);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error reading stream: {}", e.getMessage());
        }
        return new OutputBuffer(sb.toString(), truncated, bytesObserved);
    }

    private OutputBuffer getFutureOutput(Future<OutputBuffer> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new OutputBuffer("", false, 0L);
        }
    }

    private FailureType classifyFailure(int exitCode, String stdout, String stderr) {
        if (exitCode == 0) return FailureType.PASS;
        if (exitCode == 124) return FailureType.TIMEOUT;
        if (exitCode == 137) return FailureType.OOM;

        String combined = (stdout + "\n" + stderr).toLowerCase();

        if (combined.contains("modulenotfounderror") || combined.contains("classnotfoundexception") || combined.contains("package does not exist") || combined.contains("eai_again")) {
            return FailureType.DEPENDENCY_UNAVAILABLE;
        }
        if (combined.contains("syntaxerror") || combined.contains("compilation error") || combined.contains("cannot find symbol") || combined.contains("compile failed")) {
            return FailureType.COMPILATION_FAILURE;
        }
        if (combined.contains("assertionerror") || combined.contains("test failed") || combined.contains("failures:") || combined.contains("assert")) {
            return FailureType.TEST_FAILURE;
        }
        if (combined.contains("exception") || combined.contains("error")) {
            return FailureType.CODE_FAILURE;
        }

        return FailureType.UNKNOWN;
    }

    private TerminationReason classifyTerminationReason(int exitCode, FailureType failureType, String stdout, String stderr) {
        if (failureType == FailureType.TIMEOUT) {
            return TerminationReason.TIMEOUT;
        }
        if (failureType == FailureType.EXECUTION_INFRA_FAILURE) {
            return TerminationReason.EXECUTION_INFRA_FAILURE;
        }
        if (exitCode == 137) {
            return TerminationReason.OOM;
        }
        String combined = (stdout + "\n" + stderr).toLowerCase();
        if (combined.contains("out of memory") || combined.contains("java.lang.outofmemoryerror") || combined.contains("memory limit exceeded")) {
            return TerminationReason.OOM;
        }
        if (combined.contains("pids limit reached") || combined.contains("cannot fork") || combined.contains("resource temporarily unavailable") || exitCode == 134) {
            return TerminationReason.PID_LIMIT;
        }
        if (exitCode == 139 || exitCode == 125 || exitCode == 126) {
            return TerminationReason.RESOURCE_LIMIT;
        }
        return TerminationReason.NORMAL_EXIT;
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
}
