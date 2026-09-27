package com.repopilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.CodeReviewCreateRequest;
import com.repopilot.review.dto.CodeReviewResultDto;
import com.repopilot.review.dto.ValidatedFindingCandidate;
import com.repopilot.review.entity.*;
import com.repopilot.review.service.CodeReviewOrchestrationService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class Phase11_6ReviewRestApiTest {

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
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CodeReviewOrchestrationService codeReviewOrchestrationService;

    private User memberUser;
    private User nonMemberUser;
    private Project testProject;
    private Repository testRepository;
    private Task testTask;
    private CodeChange testCodeChange;
    private String memberToken;
    private String nonMemberToken;

    @BeforeEach
    void setUp() {
        memberUser = new User();
        memberUser.setEmail("member_test_" + UUID.randomUUID() + "@repopilot.com");
        memberUser.setName("Member User");
        memberUser.setPasswordHash("hashedpass");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        nonMemberUser = new User();
        nonMemberUser.setEmail("nonmember_test_" + UUID.randomUUID() + "@repopilot.com");
        nonMemberUser.setName("Non-Member User");
        nonMemberUser.setPasswordHash("hashedpass");
        nonMemberUser.setRole(UserRole.USER);
        nonMemberUser = userRepository.save(nonMemberUser);

        testProject = new Project();
        testProject.setName("Phase 11.6 REST API Test Project");
        testProject.setDescription("Description");
        testProject.setOwner(memberUser);
        testProject.setStatus(ProjectStatus.ACTIVE);
        testProject = projectRepository.save(testProject);

        ProjectMember member = new ProjectMember();
        member.setProject(testProject);
        member.setUser(memberUser);
        member.setRole(ProjectRole.MEMBER);
        projectMemberRepository.save(member);

        testRepository = new Repository();
        testRepository.setProject(testProject);
        testRepository.setOwnerName("repopilot");
        testRepository.setRepoName("test-repo");
        testRepository.setDefaultBranch("main");
        testRepository.setVisibility(RepositoryVisibility.PRIVATE);
        testRepository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        testRepository = repositoryRepository.save(testRepository);

        testTask = new Task();
        testTask.setProject(testProject);
        testTask.setRepository(testRepository);
        testTask.setCreatedBy(memberUser);
        testTask.setTitle("Test Task for Review REST API");
        testTask.setDescription("Verify Phase 11.6 endpoints");
        testTask.setStatus(TaskStatus.ANALYZING);
        testTask.setPriority(TaskPriority.HIGH);
        testTask = taskRepository.save(testTask);

        testCodeChange = new CodeChange();
        testCodeChange.setRepository(testRepository);
        testCodeChange.setTask(testTask);
        testCodeChange.setFilePath("src/main/java/App.java");
        testCodeChange.setOriginalHash("origHash123");
        testCodeChange.setProposedHash("propHash456");
        testCodeChange = codeChangeRepository.save(testCodeChange);

        memberToken = jwtTokenProvider.generateAccessToken(memberUser);
        nonMemberToken = jwtTokenProvider.generateAccessToken(nonMemberUser);
    }

    @Test
    @DisplayName("Unauthenticated request returns 401 Unauthorized")
    void unauthenticatedRequest_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepository.getId() + "/tasks/" + testTask.getId() + "/code-changes/" + testCodeChange.getId() + "/reviews"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Trigger code change review - Success returns 200 OK with CodeReviewResultDto")
    void triggerCodeChangeReview_success() throws Exception {
        UUID reviewId = UUID.randomUUID();
        CodeReviewResultDto expectedDto = createMockResultDto(reviewId, testProject.getId(), testRepository.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW);

        when(codeReviewOrchestrationService.executeCodeReview(
                eq(testProject.getId()), eq(testRepository.getId()), eq(testTask.getId()), eq(testCodeChange.getId()),
                eq(ReviewMode.CODE_CHANGE_REVIEW), any(), any()))
                .thenReturn(expectedDto);

        CodeReviewCreateRequest request = new CodeReviewCreateRequest(ReviewMode.CODE_CHANGE_REVIEW, null);

        mockMvc.perform(post("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepository.getId() + "/tasks/" + testTask.getId() + "/code-changes/" + testCodeChange.getId() + "/reviews")
                        .header("Authorization", "Bearer " + memberToken)
                        .header("Idempotency-Key", "idempotency-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.overallRiskScore").value("LOW"))
                .andExpect(jsonPath("$.summary").value("Review completed successfully."));

        verify(codeReviewOrchestrationService).executeCodeReview(
                eq(testProject.getId()), eq(testRepository.getId()), eq(testTask.getId()), eq(testCodeChange.getId()),
                eq(ReviewMode.CODE_CHANGE_REVIEW), eq("idempotency-key-123"), any());
    }

    @Test
    @DisplayName("Trigger task review - Supports X-Idempotency-Key header")
    void triggerTaskReview_supportsXIdempotencyKeyHeader() throws Exception {
        UUID reviewId = UUID.randomUUID();
        CodeReviewResultDto expectedDto = createMockResultDto(reviewId, testProject.getId(), testRepository.getId(), testTask.getId(), null, ReviewMode.TASK_IMPLEMENTATION_REVIEW);

        when(codeReviewOrchestrationService.executeCodeReview(
                eq(testProject.getId()), eq(testRepository.getId()), eq(testTask.getId()), eq(null),
                eq(ReviewMode.TASK_IMPLEMENTATION_REVIEW), eq("x-idemp-456"), any()))
                .thenReturn(expectedDto);

        mockMvc.perform(post("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepository.getId() + "/tasks/" + testTask.getId() + "/reviews")
                        .header("Authorization", "Bearer " + memberToken)
                        .header("X-Idempotency-Key", "x-idemp-456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()))
                .andExpect(jsonPath("$.reviewMode").value("TASK_IMPLEMENTATION_REVIEW"));
    }

    @Test
    @DisplayName("Get review by ID - Success returns 200 OK")
    void getReviewById_success() throws Exception {
        UUID reviewId = UUID.randomUUID();
        CodeReviewResultDto expectedDto = createMockResultDto(reviewId, testProject.getId(), testRepository.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW);

        when(codeReviewOrchestrationService.getCodeReviewResult(eq(reviewId), any()))
                .thenReturn(expectedDto);

        mockMvc.perform(get("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepository.getId() + "/tasks/" + testTask.getId() + "/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(reviewId.toString()));
    }

    @Test
    @DisplayName("Get review by ID - Path mismatch returns 404 Not Found")
    void getReviewById_pathMismatch_returns404() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        CodeReviewResultDto expectedDto = createMockResultDto(reviewId, testProject.getId(), testRepository.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW);

        when(codeReviewOrchestrationService.getCodeReviewResult(eq(reviewId), any()))
                .thenReturn(expectedDto);

        mockMvc.perform(get("/api/v1/projects/" + otherProjectId + "/repositories/" + testRepository.getId() + "/tasks/" + testTask.getId() + "/reviews/" + reviewId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Get reviews for code change - Success returns list")
    void getReviewsForCodeChange_success() throws Exception {
        UUID reviewId = UUID.randomUUID();
        CodeReviewResultDto dto = createMockResultDto(reviewId, testProject.getId(), testRepository.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW);

        when(codeReviewOrchestrationService.getCodeReviewsForCodeChange(
                eq(testProject.getId()), eq(testRepository.getId()), eq(testTask.getId()), eq(testCodeChange.getId()), any()))
                .thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepository.getId() + "/tasks/" + testTask.getId() + "/code-changes/" + testCodeChange.getId() + "/reviews")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewId").value(reviewId.toString()));
    }

    @Test
    @DisplayName("Get reviews for task - Success returns list")
    void getReviewsForTask_success() throws Exception {
        UUID reviewId = UUID.randomUUID();
        CodeReviewResultDto dto = createMockResultDto(reviewId, testProject.getId(), testRepository.getId(), testTask.getId(), null, ReviewMode.TASK_IMPLEMENTATION_REVIEW);

        when(codeReviewOrchestrationService.getCodeReviewsForTask(
                eq(testProject.getId()), eq(testRepository.getId()), eq(testTask.getId()), any()))
                .thenReturn(List.of(dto));

        mockMvc.perform(get("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepository.getId() + "/tasks/" + testTask.getId() + "/reviews")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reviewId").value(reviewId.toString()));
    }

    private CodeReviewResultDto createMockResultDto(UUID reviewId, UUID projectId, UUID repoId, UUID taskId, UUID codeChangeId, ReviewMode mode) {
        ValidatedFindingCandidate candidate = new ValidatedFindingCandidate(
                FindingType.AI_FINDING,
                "SECURITY_INJECTION",
                "CWE-89",
                FindingSeverity.HIGH,
                FindingConfidence.HIGH,
                "SQL Injection vulnerability",
                "Unsanitized input passed to SQL query",
                "src/main/java/App.java",
                10,
                15,
                FindingSide.PROPOSED,
                "hash1",
                "Use parameterized query",
                "hash1",
                "fp123",
                false
        );

        return new CodeReviewResultDto(
                reviewId,
                projectId,
                repoId,
                taskId,
                codeChangeId,
                mode,
                ReviewStatus.COMPLETED,
                "Review completed successfully.",
                "LOW",
                "ctxHash123",
                "claude-3-5-sonnet",
                120L,
                Instant.now(),
                Instant.now(),
                1,
                1,
                0,
                List.of(candidate)
        );
    }
}
