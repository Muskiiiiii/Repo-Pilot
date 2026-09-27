package com.repopilot.testing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.dto.MultiIterationOrchestrationResponseDto;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.policy.D3DecisionType;
import com.repopilot.testing.service.MultiIterationRepairOrchestrationService;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RepairLoopControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MultiIterationRepairOrchestrationService orchestrationService;

    private User memberUser;
    private User viewerUser;
    private Project testProject;
    private Repository testRepository;
    private Task testTask;
    private TaskExecution testExecution;
    private String memberToken;
    private String viewerToken;

    @BeforeEach
    void setUp() {
        memberUser = new User();
        memberUser.setEmail("member_test_" + UUID.randomUUID() + "@repopilot.com");
        memberUser.setName("Member User");
        memberUser.setPasswordHash("hashedpass");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        viewerUser = new User();
        viewerUser.setEmail("viewer_test_" + UUID.randomUUID() + "@repopilot.com");
        viewerUser.setName("Viewer User");
        viewerUser.setPasswordHash("hashedpass");
        viewerUser.setRole(UserRole.USER);
        viewerUser = userRepository.save(viewerUser);

        testProject = new Project();
        testProject.setName("Integration Test Project " + UUID.randomUUID());
        testProject.setOwner(memberUser);
        testProject = projectRepository.save(testProject);

        ProjectMember pmMember = new ProjectMember();
        pmMember.setProject(testProject);
        pmMember.setUser(memberUser);
        pmMember.setRole(ProjectRole.MEMBER);
        projectMemberRepository.save(pmMember);

        ProjectMember pmViewer = new ProjectMember();
        pmViewer.setProject(testProject);
        pmViewer.setUser(viewerUser);
        pmViewer.setRole(ProjectRole.VIEWER);
        projectMemberRepository.save(pmViewer);

        testRepository = new Repository();
        testRepository.setRepoName("integration-test-repo");
        testRepository.setOwnerName("org");
        testRepository.setProject(testProject);
        testRepository = repositoryRepository.save(testRepository);

        testTask = new Task();
        testTask.setProject(testProject);
        testTask.setRepository(testRepository);
        testTask.setCreatedBy(memberUser);
        testTask.setTitle("Integration Task");
        testTask = taskRepository.save(testTask);

        testExecution = new TaskExecution();
        testExecution.setProject(testProject);
        testExecution.setRepository(testRepository);
        testExecution.setTask(testTask);
        testExecution.setEcosystem(Ecosystem.PYTHON);
        testExecution.setStatus(ExecutionStatus.RUNNING);
        testExecution = taskExecutionRepository.save(testExecution);

        memberToken = jwtTokenProvider.generateAccessToken(memberUser);
        viewerToken = jwtTokenProvider.generateAccessToken(viewerUser);

        MultiIterationOrchestrationResponseDto orchestrationDto = new MultiIterationOrchestrationResponseDto();
        orchestrationDto.setTaskExecutionId(testExecution.getId());
        orchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);
        orchestrationDto.setTotalIterationsExecuted(1);
        orchestrationDto.setFinalOutcomePolicy("REPAIR_SUCCESS");

        when(orchestrationService.orchestrateSelfCorrection(any(), any(), any(), any(), any()))
                .thenReturn(orchestrationDto);
    }

    @Test
    @DisplayName("E2E API Test 01: POST repair by authorized MEMBER returns 202 ACCEPTED")
    void test01_TriggerRepairLoop_Succeeds() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value(testExecution.getId().toString()))
                .andExpect(jsonPath("$.status").value("TERMINATE"))
                .andExpect(jsonPath("$.alreadyCompleted").value(false));
    }

    @Test
    @DisplayName("E2E API Test 02: POST repair with Idempotency Key -> Second request returns cached 200 OK")
    void test02_IdempotentRepairLoop_ReturnsCached200() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        String key = "idempotency-key-" + UUID.randomUUID();

        mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + memberToken)
                .header("X-Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isAccepted());

        mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + memberToken)
                .header("X-Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyCompleted").value(true));
    }

    @Test
    @DisplayName("E2E API Test 03: GET status by VIEWER returns 200 OK")
    void test03_GetRepairLoopStatus_Succeeds() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        mockMvc.perform(get(url)
                .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(testExecution.getId().toString()))
                .andExpect(jsonPath("$.executionStatus").value("RUNNING"));
    }

    @Test
    @DisplayName("E2E API Test 04: POST cancel by MEMBER returns 200 OK and marks execution CANCELLED")
    void test04_CancelRepairLoop_Succeeds() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair/cancel",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.alreadyCompleted").value(true));
    }

    @Test
    @DisplayName("E2E API Test 05: VIEWER POST repair -> 403 FORBIDDEN")
    void test05_ViewerPostRepair_Forbidden() throws Exception {
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                testProject.getId(), testRepository.getId(), testTask.getId(), testExecution.getId());

        mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E2E API Test 06: IDOR - Execution queried under wrong project returns 404 NOT_FOUND")
    void test06_IdorCrossProject_Returns404() throws Exception {
        UUID wrongProjectId = UUID.randomUUID();
        String url = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/executions/%s/repair",
                wrongProjectId, testRepository.getId(), testTask.getId(), testExecution.getId());

        mockMvc.perform(get(url)
                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }
}
