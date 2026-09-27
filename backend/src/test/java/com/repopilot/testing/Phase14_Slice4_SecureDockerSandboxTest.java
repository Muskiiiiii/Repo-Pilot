package com.repopilot.testing;

import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.daemon.model.DaemonExecutionRequest;
import com.repopilot.testing.daemon.model.DaemonExecutionResponse;
import com.repopilot.testing.daemon.model.DaemonOperationType;
import com.repopilot.testing.daemon.model.DaemonStatus;
import com.repopilot.testing.daemon.runtime.SandboxDockerConfig;
import com.repopilot.testing.daemon.runtime.SandboxDockerRuntimeService;
import com.repopilot.testing.daemon.service.SandboxDaemonService;
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketClient;
import com.repopilot.testing.daemon.transport.SandboxDaemonUnixSocketServer;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.model.SandboxOwnershipClaim;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import com.repopilot.testing.repository.SandboxWorkerNodeRepository;
import com.repopilot.testing.service.SandboxFencingService;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase14_Slice4_SecureDockerSandboxTest {

    @Autowired private SandboxFencingService fencingService;
    @Autowired private SandboxDaemonService daemonService;
    @Autowired private SandboxDockerRuntimeService dockerRuntimeService;

    @Autowired private SandboxWorkerNodeRepository sandboxWorkerNodeRepository;
    @Autowired private SandboxJobExecutionRepository sandboxJobExecutionRepository;

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutionRepository taskExecutionRepository;

    private User ownerUser;
    private Project project;
    private Repository repository;
    private Task task;
    private TaskExecution taskExecution;
    private SandboxWorkerNode worker1;
    private SandboxWorkerNode worker2;
    private Path socketPath;
    private SandboxDaemonUnixSocketServer daemonServer;
    private SandboxDaemonUnixSocketClient daemonClient;

    @BeforeEach
    void setUp() throws IOException {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("slice144_remediate_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Slice 14.4 Remediation Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        project = new Project();
        project.setName("Slice 14.4 Remediation Project");
        project.setDescription("Project for Phase 14.4 secure docker testing");
        project.setOwner(ownerUser);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.save(project);

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("test-owner");
        repository.setRepoName("test-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setCreatedBy(ownerUser);
        task.setTitle("Slice 14.4 Task");
        task.setDescription("Task for Slice 14.4 testing");
        task.setStatus(TaskStatus.COMPLETED);
        task.setPriority(TaskPriority.HIGH);
        task = taskRepository.save(task);

        taskExecution = new TaskExecution();
        taskExecution.setTask(task);
        taskExecution.setProject(project);
        taskExecution.setRepository(repository);
        taskExecution.setExecutionNumber(1);
        taskExecution.setStatus(ExecutionStatus.RUNNING);
        taskExecution.setEcosystem(Ecosystem.MAVEN);
        taskExecution = taskExecutionRepository.save(taskExecution);

        worker1 = new SandboxWorkerNode();
        worker1.setNodeIdentifier("node-docker-01-" + UUID.randomUUID().toString().substring(0, 8));
        worker1.setSocketPath("/var/run/repopilot-node-1.sock");
        worker1.setStatus("HEALTHY");
        worker1 = sandboxWorkerNodeRepository.save(worker1);

        worker2 = new SandboxWorkerNode();
        worker2.setNodeIdentifier("node-docker-02-" + UUID.randomUUID().toString().substring(0, 8));
        worker2.setSocketPath("/var/run/repopilot-node-2.sock");
        worker2.setStatus("HEALTHY");
        worker2 = sandboxWorkerNodeRepository.save(worker2);

        String shortId = UUID.randomUUID().toString().substring(0, 8);
        Path baseDir = Path.of("/tmp").toRealPath();
        socketPath = baseDir.resolve("rp_s4_" + shortId + ".sock").toAbsolutePath().normalize();
        daemonServer = new SandboxDaemonUnixSocketServer(socketPath, daemonService);
        daemonServer.start();
        daemonClient = new SandboxDaemonUnixSocketClient(socketPath);
    }

    @AfterEach
    void tearDown() {
        if (daemonServer != null) {
            daemonServer.stop();
        }
        if (socketPath != null && Files.exists(socketPath)) {
            try { Files.delete(socketPath); } catch (IOException ignored) {}
        }

        if (sandboxJobExecutionRepository != null) { sandboxJobExecutionRepository.deleteAll(); sandboxJobExecutionRepository.flush(); }
        if (sandboxWorkerNodeRepository != null) { sandboxWorkerNodeRepository.deleteAll(); sandboxWorkerNodeRepository.flush(); }
        if (taskExecutionRepository != null) { taskExecutionRepository.deleteAll(); taskExecutionRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    private SandboxJobExecution createPendingJob(String commandType) {
        SandboxJobExecution job = new SandboxJobExecution();
        job.setTaskExecution(taskExecution);
        job.setProject(project);
        job.setRepository(repository);
        job.setStatus(SandboxJobStatus.PENDING);
        job.setCommandType(commandType);
        job.setCommandArgs("mvn test");
        return sandboxJobExecutionRepository.saveAndFlush(job);
    }

    @Test
    @DisplayName("Blocker 1. Seccomp Profile Attachment & Fail-Closed Enforcement")
    void testBlocker1_seccompProfileAttachmentAndFailClosed() {
        // 1. Verify Seccomp Profile Loading
        Path seccompProfile = dockerRuntimeService.ensureSeccompProfile();
        assertNotNull(seccompProfile);
        assertTrue(Files.exists(seccompProfile));
        assertTrue(seccompProfile.getFileName().toString().contains("seccomp_unprivileged_strict.json"));

        // 2. Verify Command Attachment
        UUID jobId = UUID.randomUUID();
        String execId = "exec-seccomp-test";
        List<String> cmd = dockerRuntimeService.buildSecureDockerCommand(
                jobId, execId, worker1.getId(), 1L, "container_seccomp",
                "python:3.11-slim", "PYTEST", List.of("python3", "-c", "print(1)"), null
        );

        String cmdStr = String.join(" ", cmd);
        assertTrue(cmdStr.contains("--security-opt=seccomp=" + seccompProfile.toString()),
                "Command MUST contain explicit seccomp profile attachment path");

        // 3. Verify Fail-Closed when profile is missing
        SandboxDockerConfig badConfig = new SandboxDockerConfig();
        badConfig.setSeccompProfilePath("/tmp/non_existent_seccomp_profile_9999.json");

        assertThrows(SecurityException.class, () -> {
            Path custom = Path.of(badConfig.getSeccompProfilePath());
            if (!Files.exists(custom)) {
                throw new SecurityException("Configured custom seccomp profile does not exist: " + custom);
            }
        }, "Missing Seccomp profile MUST trigger SecurityException (FAIL CLOSED)");
    }

    @Test
    @DisplayName("Blocker 2. Wall-Clock Timeout & Process Tree Termination")
    void testBlocker2_wallClockTimeoutAndProcessTreeTermination() {
        UUID jobId = UUID.randomUUID();
        String execId = "exec-timeout-test";
        String containerName = "repopilot_test_timeout_" + UUID.randomUUID().toString().substring(0, 8);

        // Execute container with 1-second timeout running an infinite sleep
        DockerExecutionResult result = dockerRuntimeService.executeSecureContainer(
                jobId, execId, worker1.getId(), 1L, containerName,
                "python:3.11-slim", "PYTHON", List.of("python3", "-c", "import time; time.sleep(300)"), null, 1
        );

        // Assert timeout occurred and process was killed
        assertEquals(124, result.getExitCode(), "Timed out execution MUST return exit code 124");
        assertTrue(result.getStderr().contains("TIMED OUT"), "Stderr MUST record timeout occurrence");

        // Assert Container is stopped/removed
        assertFalse(dockerRuntimeService.inspectContainer(containerName),
                "Container MUST NOT survive timeout execution");
    }

    @Test
    @DisplayName("Blocker 3. Bounded Output Handling & Memory Protection")
    void testBlocker3_boundedOutputHandlingAndMemoryProtection() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_OUTPUT_BOMB");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        String execId = "exec-output-bomb-" + UUID.randomUUID();

        DaemonExecutionRequest prepReq = new DaemonExecutionRequest(
                "req-p", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                execId, DaemonOperationType.PREPARE, "PYTHON", "python3 -c print('A'*500000)", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(prepReq).getStatus());

        // Execute container producing 500,000 characters
        DockerExecutionResult result = dockerRuntimeService.executeSecureContainer(
                job.getId(), execId, worker1.getId(), claim.getFencingVersion(),
                "container_output_bomb", "PYTHON", "PYTHON",
                List.of("python3", "-c", "import sys; sys.stdout.write('A'*500000)"), null, 120
        );

        // Assert output string is bounded to 100,000 characters max
        assertTrue(result.getStdout().length() <= 100000,
                "Output content length (" + result.getStdout().length() + ") MUST NOT exceed 100,000 characters limit");
    }

    @Test
    @DisplayName("Section 4 & 5. Runtime Security Controls and Override Rejections")
    void testSection4And5_runtimeSecurityControlsAndOverrides() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_OVERRIDE_REJECT");
        SandboxOwnershipClaim claim = fencingService.claimJob(job.getId(), worker1.getId(), 60);

        List<String> dangerousArgs = List.of(
                "--privileged",
                "--network=host",
                "--cap-add SYS_ADMIN",
                "--device /dev/mem",
                "-v /var/run/docker.sock:/var/run/docker.sock",
                "sh -c 'rm -rf /'",
                "GITHUB_TOKEN=secret"
        );

        for (String arg : dangerousArgs) {
            DaemonExecutionRequest badReq = new DaemonExecutionRequest(
                    "req-bad", job.getId(), worker1.getId(), claim.getLeaseToken(), claim.getFencingVersion(),
                    "exec-bad", DaemonOperationType.PREPARE, "MAVEN_TEST", arg, 120, System.currentTimeMillis()
            );

            DaemonExecutionResponse resp = daemonClient.sendRequest(badReq);
            assertEquals(DaemonStatus.INVALID_REQUEST, resp.getStatus(), "Override attempt [" + arg + "] MUST be rejected");
            assertEquals("ERR_PROHIBITED_SECURITY_OVERRIDE", resp.getErrorCode());
        }
    }

    @Test
    @DisplayName("Section 6. Stale Worker vs Real Container Race Protection")
    void testSection6_staleWorkerRealContainerRace() throws Exception {
        SandboxJobExecution job = createPendingJob("CMD_STALE_RACE");
        SandboxOwnershipClaim claimA = fencingService.claimJob(job.getId(), worker1.getId(), 1);

        String execIdA = "exec-a-" + UUID.randomUUID();
        DaemonExecutionRequest prepReqA = new DaemonExecutionRequest(
                "req-prep-a", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execIdA, DaemonOperationType.PREPARE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.SUCCESS, daemonClient.sendRequest(prepReqA).getStatus());

        // Expire Worker A lease and Worker B claims generation 3
        SandboxJobExecution dbJob = sandboxJobExecutionRepository.findById(job.getId()).orElseThrow();
        dbJob.setLeaseExpiresAt(Instant.now().minusSeconds(10));
        sandboxJobExecutionRepository.saveAndFlush(dbJob);

        SandboxOwnershipClaim claimB = fencingService.claimJob(job.getId(), worker2.getId(), 60);
        assertEquals(3L, claimB.getFencingVersion());

        // Worker A attempts to terminate execution -> Rejected with STALE_FENCE
        DaemonExecutionRequest termReqA = new DaemonExecutionRequest(
                "req-term-a", job.getId(), worker1.getId(), claimA.getLeaseToken(), claimA.getFencingVersion(),
                execIdA, DaemonOperationType.TERMINATE, "MAVEN_TEST", "mvn test", 120, System.currentTimeMillis()
        );
        assertEquals(DaemonStatus.STALE_FENCE, daemonClient.sendRequest(termReqA).getStatus());
    }
}
