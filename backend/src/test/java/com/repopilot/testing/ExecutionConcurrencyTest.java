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
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.TestRun;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.testing.service.DockerSandboxExecutorService;
import com.repopilot.testing.service.FailureClassifierServiceImpl;
import com.repopilot.testing.service.TaskExecutionServiceImpl;
import com.repopilot.testing.service.WorkspaceManagerService;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionConcurrencyTest {

    @Mock private ProjectAuthorizationService authorizationService;
    @Mock private RepositoryRepository repositoryRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private TaskExecutionRepository taskExecutionRepository;
    @Mock private CodeChangeRepository codeChangeRepository;
    @Mock private TestRunRepository testRunRepository;
    @Mock private WorkspaceManagerService workspaceManagerService;
    @Mock private DockerSandboxExecutorService dockerSandboxExecutorService;
    @Mock private AuditLogService auditLogService;

    private TaskExecutionServiceImpl taskExecutionService;

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask;

    @BeforeEach
    void setUp() {
        com.repopilot.testing.parser.TestResultParserCompositeService parserService = new com.repopilot.testing.parser.TestResultParserCompositeService(java.util.List.of());
        ObjectMapper objectMapper = new ObjectMapper();
        FailureClassifierServiceImpl classifierService = new FailureClassifierServiceImpl(parserService, objectMapper);
        taskExecutionService = new TaskExecutionServiceImpl(
                authorizationService, repositoryRepository, taskRepository,
                taskExecutionRepository, codeChangeRepository, testRunRepository,
                workspaceManagerService, dockerSandboxExecutorService, parserService,
                classifierService, auditLogService, objectMapper
        );

        testUser = new User();
        testUser.setId(UUID.randomUUID());

        testProject = new Project();
        testProject.setId(UUID.randomUUID());

        testRepo = new Repository();
        testRepo.setId(UUID.randomUUID());
        testRepo.setProject(testProject);

        testTask = new Task();
        testTask.setId(UUID.randomUUID());
        testTask.setProject(testProject);
        testTask.setRepository(testRepo);
    }

    @Test
    void executeTask_duplicateConcurrentExecution_rejectsWithConflict() {
        when(authorizationService.getAuthorizedProject(eq(testProject.getId()), eq(testUser))).thenReturn(testProject);
        when(repositoryRepository.findById(eq(testRepo.getId()))).thenReturn(Optional.of(testRepo));
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(eq(testTask.getId()), eq(testProject.getId()), eq(testRepo.getId()))).thenReturn(Optional.of(testTask));
        when(taskExecutionRepository.findFirstByTaskIdOrderByExecutionNumberDesc(eq(testTask.getId()))).thenReturn(Optional.empty());
        when(taskExecutionRepository.save(any(TaskExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workspaceManagerService.calculateTreeDigest(any())).thenReturn("digest_123");
        when(workspaceManagerService.materializeWorkspace(any(), any())).thenReturn(Paths.get("/tmp/wsp"));

        DockerExecutionResult mockResult = new DockerExecutionResult(0, "OK", "", 100L, "c1", FailureType.PASS, false, false, "python3");
        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any())).thenReturn(mockResult);

        TaskExecutionRequestDto request = new TaskExecutionRequestDto(Ecosystem.PYTHON, null);

        // First execution succeeds
        assertDoesNotThrow(() -> taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser));

        // When active execution exists in DB (PREPARING or RUNNING), subsequent execution attempt is rejected with 409 CONFLICT
        when(taskExecutionRepository.existsByTaskIdAndStatusIn(eq(testTask.getId()), anyList())).thenReturn(true);
        ApiException ex = assertThrows(ApiException.class, () -> taskExecutionService.executeTask(testProject.getId(), testRepo.getId(), testTask.getId(), request, testUser));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("EXECUTION_IN_PROGRESS", ex.getCode());
    }
}
