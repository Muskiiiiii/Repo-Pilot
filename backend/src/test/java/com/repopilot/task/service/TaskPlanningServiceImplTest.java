package com.repopilot.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.common.exception.ApiException;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.repository.EmbeddingRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.dto.PlanResponseDto;
import com.repopilot.task.dto.TaskCreateRequestDto;
import com.repopilot.task.dto.TaskResponseDto;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskPlanningServiceImplTest {

    @Mock private ProjectAuthorizationService authorizationService;
    @Mock private RepositoryRepository repositoryRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private PlanRepository planRepository;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private AiServiceClient aiServiceClient;

    private ObjectMapper objectMapper;
    private TaskPlanningServiceImpl taskPlanningService;

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        taskPlanningService = new TaskPlanningServiceImpl(
                authorizationService,
                repositoryRepository,
                taskRepository,
                planRepository,
                embeddingRepository,
                aiServiceClient,
                objectMapper
        );

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("developer@repopilot.ai");

        testProject = new Project();
        testProject.setId(projectId);
        testProject.setName("Test Project");

        testRepo = new Repository();
        testRepo.setId(repositoryId);
        testRepo.setProject(testProject);
        testRepo.setRepoName("test-repo");
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
    }

    @Test
    void createTask_success() {
        when(authorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(testRepo));

        Task savedTask = new Task();
        savedTask.setId(taskId);
        savedTask.setProject(testProject);
        savedTask.setRepository(testRepo);
        savedTask.setTitle("Add email validation");
        savedTask.setDescription("Validate email on signup");
        savedTask.setStatus(TaskStatus.CREATED);

        when(taskRepository.save(any(Task.class))).thenReturn(savedTask);

        TaskCreateRequestDto request = new TaskCreateRequestDto("Add email validation", "Validate email on signup");
        TaskResponseDto response = taskPlanningService.createTask(projectId, repositoryId, request, testUser);

        assertNotNull(response);
        assertEquals(taskId, response.getId());
        assertEquals("Add email validation", response.getTitle());
        assertEquals("CREATED", response.getStatus());
        verify(authorizationService).getAuthorizedProject(projectId, testUser);
    }

    @Test
    void createTask_unauthorized_throws403() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied"))
                .when(authorizationService).getAuthorizedProject(projectId, testUser);

        TaskCreateRequestDto request = new TaskCreateRequestDto("Add email validation", "Validate email");

        ApiException ex = assertThrows(ApiException.class, () ->
                taskPlanningService.createTask(projectId, repositoryId, request, testUser)
        );
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void generatePlan_unindexedRepository_throws400() {
        testRepo.setIndexStatus(RepositoryIndexStatus.NOT_INDEXED);

        Task task = new Task();
        task.setId(taskId);
        task.setProject(testProject);
        task.setRepository(testRepo);
        task.setTitle("Add email validation");
        task.setDescription("Validate email");

        when(authorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId))
                .thenReturn(Optional.of(task));

        ApiException ex = assertThrows(ApiException.class, () ->
                taskPlanningService.generatePlan(projectId, repositoryId, taskId, testUser)
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("REPOSITORY_NOT_INDEXED", ex.getCode());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void generatePlan_success_with_evidence() {
        Task task = new Task();
        task.setId(taskId);
        task.setProject(testProject);
        task.setRepository(testRepo);
        task.setTitle("Add email validation");
        task.setDescription("Validate email");

        when(authorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId))
                .thenReturn(Optional.of(task));

        Map<String, Object> understandingMap = Map.of(
                "problem_statement", "Validate email format",
                "search_queries", List.of("email validation")
        );
        when(aiServiceClient.analyzeIssue(anyString(), anyString())).thenReturn(understandingMap);
        when(aiServiceClient.generateQueryEmbedding("email validation")).thenReturn("[0.1, 0.2]");

        UUID chunkId = UUID.randomUUID();
        Object[] row = new Object[]{
                chunkId, 0, "public void register() {}", "register", "METHOD", 1, 20, "src/Auth.java", "Java", 0.1500
        };
        when(embeddingRepository.searchSemanticChunksNative(eq(repositoryId), anyString(), eq(5)))
                .thenReturn(List.<Object[]>of(row));

        Map<String, Object> planMap = Map.of(
                "summary", "Implementation plan for email validation",
                "problem_analysis", "Email format needs regex validation",
                "relevant_files", List.of("src/Auth.java"),
                "confidence_level", "HIGH",
                "confidence_score", 0.88,
                "is_ambiguous", false
        );
        when(aiServiceClient.generatePlan(anyMap())).thenReturn(planMap);

        Plan savedPlan = new Plan();
        savedPlan.setId(UUID.randomUUID());
        savedPlan.setTask(task);
        savedPlan.setObjective("Implementation plan for email validation");
        savedPlan.setProblemAnalysis("Email format needs regex validation");
        savedPlan.setConfidenceLevel("HIGH");
        savedPlan.setIsAmbiguous(false);
        savedPlan.setStatus("GENERATED");

        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        PlanResponseDto response = taskPlanningService.generatePlan(projectId, repositoryId, taskId, testUser);

        assertNotNull(response);
        assertEquals("Implementation plan for email validation", response.getSummary());
        assertEquals("HIGH", response.getConfidenceLevel());
        assertFalse(response.getIsAmbiguous());
        verify(taskRepository, atLeastOnce()).save(task);
    }

    @Test
    void generatePlan_ambiguousIssue_setsIsAmbiguousTrue() {
        Task task = new Task();
        task.setId(taskId);
        task.setProject(testProject);
        task.setRepository(testRepo);
        task.setTitle("Fix something vague");
        task.setDescription("Vague description");

        when(authorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId))
                .thenReturn(Optional.of(task));

        Map<String, Object> understandingMap = Map.of(
                "problem_statement", "Vague problem",
                "search_queries", List.of("vague search")
        );
        when(aiServiceClient.analyzeIssue(anyString(), anyString())).thenReturn(understandingMap);
        when(aiServiceClient.generateQueryEmbedding(anyString())).thenReturn("[0.1]");
        when(embeddingRepository.searchSemanticChunksNative(eq(repositoryId), anyString(), eq(5)))
                .thenReturn(Collections.emptyList());

        Map<String, Object> planMap = Map.of(
                "summary", "Ambiguous request plan",
                "problem_analysis", "Cannot identify target files from evidence",
                "confidence_level", "LOW",
                "confidence_score", 0.25,
                "is_ambiguous", true,
                "ambiguity_reason", "No matching codebase evidence chunks were found."
        );
        when(aiServiceClient.generatePlan(anyMap())).thenReturn(planMap);

        Plan savedPlan = new Plan();
        savedPlan.setId(UUID.randomUUID());
        savedPlan.setTask(task);
        savedPlan.setObjective("Ambiguous request plan");
        savedPlan.setConfidenceLevel("LOW");
        savedPlan.setIsAmbiguous(true);
        savedPlan.setAmbiguityReason("No matching codebase evidence chunks were found.");
        savedPlan.setStatus("GENERATED");

        when(planRepository.save(any(Plan.class))).thenReturn(savedPlan);

        PlanResponseDto response = taskPlanningService.generatePlan(projectId, repositoryId, taskId, testUser);

        assertNotNull(response);
        assertTrue(response.getIsAmbiguous());
        assertEquals("LOW", response.getConfidenceLevel());
        assertEquals("No matching codebase evidence chunks were found.", response.getAmbiguityReason());
    }

    @Test
    void getPlan_notFound_throws404() {
        Task task = new Task();
        task.setId(taskId);

        when(authorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId))
                .thenReturn(Optional.of(task));
        when(planRepository.findByTaskId(taskId)).thenReturn(Collections.emptyList());

        ApiException ex = assertThrows(ApiException.class, () ->
                taskPlanningService.getPlan(projectId, repositoryId, taskId, testUser)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("PLAN_NOT_FOUND", ex.getCode());
    }
}
