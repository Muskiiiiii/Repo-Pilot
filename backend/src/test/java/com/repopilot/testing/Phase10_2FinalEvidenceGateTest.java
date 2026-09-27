package com.repopilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.dto.TaskExecutionRequestDto;
import com.repopilot.testing.dto.TaskExecutionResponseDto;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.TerminationReason;
import com.repopilot.testing.parser.*;
import com.repopilot.testing.policy.TestRunnerPolicy;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.testing.service.DockerSandboxExecutorServiceImpl;
import com.repopilot.testing.service.FailureClassifierServiceImpl;
import com.repopilot.testing.service.TaskExecutionServiceImpl;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class Phase10_2FinalEvidenceGateTest {

    @TempDir
    Path tempDir;

    @Mock private ProjectAuthorizationService authorizationService;
    @Mock private RepositoryRepository repositoryRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskExecutionRepository taskExecutionRepository;
    @Mock private CodeChangeRepository codeChangeRepository;
    @Mock private TestRunRepository testRunRepository;
    @Mock private AuditLogService auditLogService;

    private WorkspaceManagerServiceImpl workspaceManagerService;
    private DockerSandboxExecutorServiceImpl dockerSandboxExecutorService;
    private TestResultParserCompositeService parserCompositeService;
    private TaskExecutionServiceImpl taskExecutionService;

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask;

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);

        workspaceManagerService = new WorkspaceManagerServiceImpl();
        ReflectionTestUtils.setField(workspaceManagerService, "workspaceBasePath", tempDir.resolve("workspaces").toString());
        ReflectionTestUtils.setField(workspaceManagerService, "baseStoragePath", tempDir.resolve("repositories").toString());

        dockerSandboxExecutorService = new DockerSandboxExecutorServiceImpl();
        ReflectionTestUtils.setField(dockerSandboxExecutorService, "dockerBinary", "docker");

        parserCompositeService = new TestResultParserCompositeService(List.of(
                new PythonTestResultParser(),
                new NodeTestResultParser(),
                new MavenTestResultParser(),
                new GradleTestResultParser(),
                new GoTestResultParser(),
                new RustTestResultParser()
        ));

        ObjectMapper objectMapper = new ObjectMapper();
        FailureClassifierServiceImpl classifierService = new FailureClassifierServiceImpl(parserCompositeService, objectMapper);

        taskExecutionService = new TaskExecutionServiceImpl(
                authorizationService, repositoryRepository, taskRepository,
                taskExecutionRepository, codeChangeRepository, testRunRepository,
                workspaceManagerService, dockerSandboxExecutorService, parserCompositeService,
                classifierService, auditLogService, objectMapper
        );
        ReflectionTestUtils.setField(taskExecutionService, "baseStoragePath", tempDir.resolve("repositories").toString());

        testUser = new User();
        testUser.setId(UUID.randomUUID());

        testProject = new Project();
        testProject.setId(UUID.randomUUID());

        UUID repoId = UUID.randomUUID();
        Path masterRepoDir = tempDir.resolve("repositories").resolve(repoId.toString());
        Files.createDirectories(masterRepoDir);

        testRepo = new Repository();
        testRepo.setId(repoId);
        testRepo.setProject(testProject);

        testTask = new Task();
        testTask.setId(UUID.randomUUID());
        testTask.setProject(testProject);
        testTask.setRepository(testRepo);

        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(eq(testTask.getId()), eq(testProject.getId()), eq(testRepo.getId()))).thenReturn(Optional.of(testTask));
        when(taskExecutionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(testRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(codeChangeRepository.findByTaskId(any())).thenReturn(Collections.emptyList());
    }

    @Test
    @DisplayName("1. Real Pass Execution through full pipeline")
    void testRealPassExecution() throws IOException {
        Path masterRepoDir = tempDir.resolve("repositories").resolve(testRepo.getId().toString());
        Files.writeString(masterRepoDir.resolve("test_pass.py"),
                "import unittest\n" +
                "class TestPass(unittest.TestCase):\n" +
                "    def test_success(self):\n" +
                "        self.assertEqual(1 + 1, 2)\n");

        TaskExecutionRequestDto request = new TaskExecutionRequestDto();
        request.setEcosystem(Ecosystem.PYTHON);
        request.setTestTarget("test_pass.py");

        TaskExecutionResponseDto response = taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser);

        assertNotNull(response.getExecutionId());
        assertEquals("COMPLETED", response.getStatus().name());
        assertNotNull(response.getTestRun());
        assertEquals("PASSED", response.getTestRun().getStatus());
        assertEquals(0, response.getTestRun().getExitCode());
        assertEquals(TerminationReason.NORMAL_EXIT, response.getTestRun().getTerminationReason());
        assertEquals(1, response.getTestRun().getTestsRun());
        assertEquals(1, response.getTestRun().getPassedCount());
        assertEquals(0, response.getTestRun().getFailuresCount());
        assertEquals("PARSED", response.getTestRun().getParserStatus());

        // Verify cleanup
        File workspaceDir = tempDir.resolve("workspaces").resolve(response.getExecutionId().toString()).toFile();
        assertFalse(workspaceDir.exists(), "Workspace directory must be cleaned up");
    }

    @Test
    @DisplayName("2. Real Test Failure through full pipeline")
    void testRealTestFailure() throws IOException {
        Path masterRepoDir = tempDir.resolve("repositories").resolve(testRepo.getId().toString());
        Files.writeString(masterRepoDir.resolve("test_fail.py"),
                "import unittest\n" +
                "class TestFail(unittest.TestCase):\n" +
                "    def test_failure(self):\n" +
                "        self.assertEqual(1 + 1, 99)\n");

        TaskExecutionRequestDto request = new TaskExecutionRequestDto();
        request.setEcosystem(Ecosystem.PYTHON);
        request.setTestTarget("test_fail.py");

        TaskExecutionResponseDto response = taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser);

        assertEquals("FAILED", response.getStatus().name());
        assertNotNull(response.getTestRun());
        assertEquals("FAILED", response.getTestRun().getStatus());
        assertNotEquals(0, response.getTestRun().getExitCode());
        assertEquals(FailureType.TEST_FAILURE, response.getTestRun().getFailureType());
        assertEquals(TerminationReason.NORMAL_EXIT, response.getTestRun().getTerminationReason());
        assertEquals(1, response.getTestRun().getTestsRun());
        assertEquals(1, response.getTestRun().getFailuresCount());
        assertEquals("PARSED", response.getTestRun().getParserStatus());
    }

    @Test
    @DisplayName("3. Real Timeout Execution force terminates container and workspace")
    void testRealTimeoutExecution() throws IOException {
        Path masterRepoDir = tempDir.resolve("repositories").resolve(testRepo.getId().toString());
        Files.writeString(masterRepoDir.resolve("test_timeout.py"),
                "import unittest, time\n" +
                "class TestTimeout(unittest.TestCase):\n" +
                "    def test_sleep(self):\n" +
                "        time.sleep(15)\n");

        UUID executionId = UUID.randomUUID();
        Path workspaceRepoDir = workspaceManagerService.materializeWorkspace(executionId, testRepo);

        // Force a short timeout of 2 seconds
        DockerExecutionResult result = dockerSandboxExecutorService.executeSandboxTest(
                executionId, testTask.getId(), Ecosystem.PYTHON, "test_timeout.py", workspaceRepoDir, Duration.ofSeconds(2)
        );

        workspaceManagerService.cleanupWorkspace(executionId);

        assertEquals(124, result.getExitCode());
        assertEquals(FailureType.TIMEOUT, result.getFailureType());
        assertEquals(TerminationReason.TIMEOUT, result.getTerminationReason());

        File workspaceDir = tempDir.resolve("workspaces").resolve(executionId.toString()).toFile();
        assertFalse(workspaceDir.exists(), "Workspace directory must be deleted on timeout");
    }

    @Test
    @DisplayName("4. Real Large Output Test drains stream without pipe deadlock and records bytes")
    void testRealLargeOutput() throws IOException {
        Path masterRepoDir = tempDir.resolve("repositories").resolve(testRepo.getId().toString());
        Files.writeString(masterRepoDir.resolve("test_large_output.py"),
                "import unittest, sys\n" +
                "class TestLarge(unittest.TestCase):\n" +
                "    def test_large(self):\n" +
                "        for i in range(25000):\n" +
                "            sys.stdout.write('STDOUT_LINE_' + str(i) + '\\n')\n" +
                "            sys.stderr.write('STDERR_LINE_' + str(i) + '\\n')\n");

        UUID executionId = UUID.randomUUID();
        Path workspaceRepoDir = workspaceManagerService.materializeWorkspace(executionId, testRepo);

        DockerExecutionResult result = dockerSandboxExecutorService.executeSandboxTest(
                executionId, testTask.getId(), Ecosystem.PYTHON, "test_large_output.py", workspaceRepoDir, Duration.ofSeconds(30)
        );

        workspaceManagerService.cleanupWorkspace(executionId);

        assertTrue(result.isStdoutTruncated(), "stdout must be marked truncated");
        assertTrue(result.isStderrTruncated(), "stderr must be marked truncated");
        assertTrue(result.getStdoutBytesObserved() > 100000, "stdoutBytesObserved must reflect total bytes");
        assertTrue(result.getStderrBytesObserved() > 100000, "stderrBytesObserved must reflect total bytes");
        assertTrue(result.getStdout().length() <= 100000, "Buffered stdout must be bounded by limit");
    }

    @Test
    @DisplayName("5. Real Dependency Failure classification")
    void testDependencyFailure() throws IOException {
        Path masterRepoDir = tempDir.resolve("repositories").resolve(testRepo.getId().toString());
        Files.writeString(masterRepoDir.resolve("test_dep.py"),
                "import non_existent_dependency_xyz_999\n");

        TaskExecutionRequestDto request = new TaskExecutionRequestDto();
        request.setEcosystem(Ecosystem.PYTHON);
        request.setTestTarget("test_dep.py");

        TaskExecutionResponseDto response = taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser);

        assertEquals("FAILED", response.getStatus().name());
        assertEquals(FailureType.DEPENDENCY_UNAVAILABLE, response.getTestRun().getFailureType());
    }

    @Test
    @DisplayName("6. Command Injection Security Validation")
    void testCommandInjectionSecurity() {
        String[] MaliciousTargets = new String[]{
                "test.py; rm -rf /",
                "test.py && cat /etc/passwd",
                "test.py || echo hacked",
                "test.py | grep secret",
                "test.py > /tmp/hacked",
                "test.py < /etc/shadow",
                "test.py `id`",
                "test.py $(whoami)",
                "../outside.py",
                "/etc/passwd",
                "test.py\0.py"
        };

        for (String target : MaliciousTargets) {
            TaskExecutionRequestDto request = new TaskExecutionRequestDto();
            request.setEcosystem(Ecosystem.PYTHON);
            request.setTestTarget(target);

            assertThrows(ApiException.class, () ->
                    taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser),
                    "Failed to reject malicious target: " + target
            );
        }
    }

    @Test
    @DisplayName("7. Master Repository Digest Integrity Check")
    void testMasterRepoIntegrity() throws IOException {
        Path masterRepoDir = tempDir.resolve("repositories").resolve(testRepo.getId().toString());
        Files.writeString(masterRepoDir.resolve("main.py"), "print('hello world')\n");

        String digestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        TaskExecutionRequestDto request = new TaskExecutionRequestDto();
        request.setEcosystem(Ecosystem.PYTHON);
        request.setTestTarget("main.py");

        taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser);

        String digestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        assertEquals(digestBefore, digestAfter, "Master repository tree digest must remain byte-for-byte identical");
    }

    @Test
    @DisplayName("8. Malformed output stream handling")
    void testMalformedOutputHandling() {
        byte[] malformedBytes = new byte[]{(byte) 0x80, (byte) 0x81, (byte) 0xFE, (byte) 0xFF, 0x00, 0x01};
        String malformedString = new String(malformedBytes, StandardCharsets.ISO_8859_1);

        ParsedTestEvidence evidence = parserCompositeService.parse(Ecosystem.PYTHON, malformedString, malformedString);

        assertNotNull(evidence);
        assertEquals("UNPARSED", evidence.getParserStatus());
        assertEquals(0, evidence.getTestsRun());
    }
}
