package com.repopilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.dto.TaskExecutionRequestDto;
import com.repopilot.testing.dto.TaskExecutionResponseDto;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.TestRun;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.testing.service.DockerSandboxExecutorService;
import com.repopilot.testing.service.TaskExecutionServiceImpl;
import com.repopilot.testing.service.WorkspaceManagerServiceImpl;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SyntheticRepositoryE2ETest {

    @TempDir
    Path tempDir;

    private WorkspaceManagerServiceImpl workspaceManagerService;
    private TaskExecutionServiceImpl taskExecutionService;

    private ProjectAuthorizationService authorizationService;
    private RepositoryRepository repositoryRepository;
    private TaskRepository taskRepository;
    private TaskExecutionRepository taskExecutionRepository;
    private CodeChangeRepository codeChangeRepository;
    private TestRunRepository testRunRepository;
    private DockerSandboxExecutorService dockerSandboxExecutorService;
    private AuditLogService auditLogService;

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask;
    private Path masterRepoDir;

    @BeforeEach
    void setUp() throws IOException {
        authorizationService = mock(ProjectAuthorizationService.class);
        repositoryRepository = mock(RepositoryRepository.class);
        taskRepository = mock(TaskRepository.class);
        taskExecutionRepository = mock(TaskExecutionRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        testRunRepository = mock(TestRunRepository.class);
        dockerSandboxExecutorService = mock(DockerSandboxExecutorService.class);
        auditLogService = mock(AuditLogService.class);

        workspaceManagerService = new WorkspaceManagerServiceImpl();

        Path workspaceBaseDir = tempDir.resolve("workspaces");
        UUID repoId = UUID.randomUUID();
        masterRepoDir = tempDir.resolve(repoId.toString());

        Files.createDirectories(masterRepoDir);
        Files.createDirectories(workspaceBaseDir);

        ReflectionTestUtils.setField(workspaceManagerService, "baseStoragePath", tempDir.toString());
        ReflectionTestUtils.setField(workspaceManagerService, "workspaceBasePath", workspaceBaseDir.toString());

        Files.writeString(masterRepoDir.resolve("test_script.py"), "def test_func():\n    assert True\n");

        testUser = new User();
        testUser.setId(UUID.randomUUID());

        testProject = new Project();
        testProject.setId(UUID.randomUUID());

        testRepo = new Repository();
        testRepo.setId(repoId);
        testRepo.setProject(testProject);

        testTask = new Task();
        testTask.setId(UUID.randomUUID());
        testTask.setProject(testProject);
        testTask.setRepository(testRepo);

        com.repopilot.testing.parser.TestResultParserCompositeService parserService = new com.repopilot.testing.parser.TestResultParserCompositeService(java.util.List.of());
        com.repopilot.testing.service.FailureClassifierServiceImpl classifierService = new com.repopilot.testing.service.FailureClassifierServiceImpl(parserService, new ObjectMapper());
        taskExecutionService = new TaskExecutionServiceImpl(
                authorizationService, repositoryRepository, taskRepository,
                taskExecutionRepository, codeChangeRepository, testRunRepository,
                workspaceManagerService, dockerSandboxExecutorService, parserService, classifierService, auditLogService, new ObjectMapper()
        );
        ReflectionTestUtils.setField(taskExecutionService, "baseStoragePath", tempDir.toString());

        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(eq(testTask.getId()), eq(testProject.getId()), eq(testRepo.getId()))).thenReturn(Optional.of(testTask));
        when(taskExecutionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(testRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void executePipeline_successfulExecution_returnsCompletedAndPassStatus() throws IOException {
        CodeChange change = new CodeChange();
        change.setFilePath("test_script.py");
        change.setOperation("MODIFY");
        change.setNewContent("def test_func():\n    assert 1 == 1\n");

        when(codeChangeRepository.findByTaskId(eq(testTask.getId()))).thenReturn(List.of(change));

        DockerExecutionResult passResult = new DockerExecutionResult(0, "1 passed in 0.01s", "", 120L, "c123", FailureType.PASS, false, false, "python3 -m pytest");
        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any())).thenReturn(passResult);

        String digestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        TaskExecutionRequestDto request = new TaskExecutionRequestDto(Ecosystem.PYTHON, "test_script.py");
        TaskExecutionResponseDto response = taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser);

        assertNotNull(response);
        assertEquals(ExecutionStatus.COMPLETED, response.getStatus());
        assertNotNull(response.getTestRun());
        assertEquals("PASSED", response.getTestRun().getStatus());
        assertEquals(FailureType.PASS, response.getTestRun().getFailureType());
        assertEquals(0, response.getTestRun().getExitCode());

        String digestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        assertEquals(digestBefore, digestAfter, "Master repository tree digest MUST remain identical!");
    }

    @Test
    void executePipeline_failingExecution_returnsFailedAndFailureType() throws IOException {
        CodeChange change = new CodeChange();
        change.setFilePath("test_script.py");
        change.setOperation("MODIFY");
        change.setNewContent("def test_func():\n    assert 1 == 2\n");

        when(codeChangeRepository.findByTaskId(eq(testTask.getId()))).thenReturn(List.of(change));

        DockerExecutionResult failResult = new DockerExecutionResult(1, "1 failed in 0.01s", "AssertionError: assert 1 == 2", 150L, "c124", FailureType.TEST_FAILURE, false, false, "python3 -m pytest");
        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any())).thenReturn(failResult);

        String digestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        TaskExecutionRequestDto request = new TaskExecutionRequestDto(Ecosystem.PYTHON, "test_script.py");
        TaskExecutionResponseDto response = taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser);

        assertNotNull(response);
        assertEquals(ExecutionStatus.FAILED, response.getStatus());
        assertNotNull(response.getTestRun());
        assertEquals("FAILED", response.getTestRun().getStatus());
        assertEquals(FailureType.TEST_FAILURE, response.getTestRun().getFailureType());
        assertEquals(1, response.getTestRun().getExitCode());

        String digestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        assertEquals(digestBefore, digestAfter, "Master repository tree digest MUST remain identical!");
    }
}
