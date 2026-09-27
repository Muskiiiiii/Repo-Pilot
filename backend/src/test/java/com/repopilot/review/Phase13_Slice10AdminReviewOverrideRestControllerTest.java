package com.repopilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.repository.AuditLogRepository;
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
import com.repopilot.review.dto.AdminReviewOverrideRequest;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.ReviewContextService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase13_Slice10AdminReviewOverrideRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

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
    private CodeReviewRepository codeReviewRepository;

    @Autowired
    private ReviewFindingRepository reviewFindingRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ReviewContextService reviewContextService;

    private User ownerUser;
    private User adminUser;
    private User memberUser;
    private User viewerUser;

    private String ownerToken;
    private String adminToken;
    private String memberToken;
    private String viewerToken;

    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private CodeReview review;

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("rest_slice10_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("REST Slice 10 Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerUser);

        adminUser = new User();
        adminUser.setEmail("rest_slice10_admin_" + UUID.randomUUID() + "@example.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("REST Slice 10 Admin");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);
        adminToken = jwtTokenProvider.generateAccessToken(adminUser);

        memberUser = new User();
        memberUser.setEmail("rest_slice10_member_" + UUID.randomUUID() + "@example.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("REST Slice 10 Member");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);
        memberToken = jwtTokenProvider.generateAccessToken(memberUser);

        viewerUser = new User();
        viewerUser.setEmail("rest_slice10_viewer_" + UUID.randomUUID() + "@example.com");
        viewerUser.setPasswordHash("hash");
        viewerUser.setName("REST Slice 10 Viewer");
        viewerUser.setRole(UserRole.USER);
        viewerUser = userRepository.save(viewerUser);
        viewerToken = jwtTokenProvider.generateAccessToken(viewerUser);

        project = new Project();
        project.setName("Phase 13 Slice 10 REST Test Project");
        project.setDescription("Project for Slice 10 REST controller testing");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwner(ownerUser);
        project = projectRepository.save(project);

        projectMemberRepository.save(new ProjectMember(project, ownerUser, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(project, adminUser, ProjectRole.ADMIN));
        projectMemberRepository.save(new ProjectMember(project, memberUser, ProjectRole.MEMBER));
        projectMemberRepository.save(new ProjectMember(project, viewerUser, ProjectRole.VIEWER));

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("test-rest-owner");
        repository.setRepoName("test-rest-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setTitle("REST Slice 10 Task");
        task.setDescription("Task for Slice 10 REST testing");
        task.setStatus(TaskStatus.ANALYZING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreatedBy(ownerUser);
        task = taskRepository.save(task);

        codeChange = new CodeChange();
        codeChange.setTask(task);
        codeChange.setRepository(repository);
        codeChange.setFilePath("src/main/java/Main.java");
        codeChange.setOperation("MODIFY");
        codeChange.setDiff("--- a/Main.java\n+++ b/Main.java\n@@ -1 +1 @@\n-v1\n+v2");
        codeChange.setOriginalHash("hashA");
        codeChange.setProposedHash("hashB");
        codeChange = codeChangeRepository.save(codeChange);

        ReviewContext ctx = reviewContextService.buildReviewContext(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser
        );

        review = new CodeReview();
        review.setProject(project);
        review.setRepository(repository);
        review.setTask(task);
        review.setCodeChange(codeChange);
        review.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review.setStatus(ReviewStatus.COMPLETED);
        review.setReviewContextHash(ctx.reviewContextHash());
        review.setModelIdentifier("model-1");
        review.setAiInputHash("ai_input_hash_rest");
        review.setSummary("Found blocking issue");
        review = codeReviewRepository.save(review);
    }

    @AfterEach
    void tearDown() {
        if (reviewFindingRepository != null) reviewFindingRepository.deleteAllInBatch();
        if (codeReviewRepository != null) codeReviewRepository.deleteAllInBatch();
        if (codeChangeRepository != null) codeChangeRepository.deleteAllInBatch();
        if (auditLogRepository != null) auditLogRepository.deleteAllInBatch();
        if (taskRepository != null) taskRepository.deleteAllInBatch();
        if (repositoryRepository != null) repositoryRepository.deleteAllInBatch();
        if (projectMemberRepository != null) projectMemberRepository.deleteAllInBatch();
        if (projectRepository != null) projectRepository.deleteAllInBatch();
        if (userRepository != null) userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("REST 1: Unauthenticated request returns 401 Unauthorized")
    void testRest_Unauthenticated_Returns401() throws Exception {
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Valid override reason text for testing.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("REST 2: MEMBER role token returns 403 Forbidden")
    void testRest_MemberRole_Returns403() throws Exception {
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Valid override reason text for testing.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REST 3: VIEWER role token returns 403 Forbidden")
    void testRest_ViewerRole_Returns403() throws Exception {
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Valid override reason text for testing.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REST 4: ADMIN role token returns 200 OK and complete response DTO")
    void testRest_AdminRole_Returns200() throws Exception {
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Valid override reason text by project admin.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(review.getId().toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.adminOverrideReason").value("Valid override reason text by project admin."))
                .andExpect(jsonPath("$.adminOverrideByUserId").value(adminUser.getId().toString()))
                .andExpect(jsonPath("$.adminOverrideByUserName").value(adminUser.getName()));
    }

    @Test
    @DisplayName("REST 5: OWNER role token returns 200 OK (ADMIN-equivalent)")
    void testRest_OwnerRole_Returns200() throws Exception {
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Valid override reason text by project owner.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminOverrideByUserId").value(ownerUser.getId().toString()));
    }

    @Test
    @DisplayName("REST 6: Non-existent review ID returns 404 Not Found")
    void testRest_NonExistentReview_Returns404() throws Exception {
        UUID fakeReviewId = UUID.randomUUID();
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Valid override reason text for testing.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), fakeReviewId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REST 7: Short justification reason returns 400 Bad Request")
    void testRest_InvalidJustificationLength_Returns400() throws Exception {
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Short");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("REST 8: Already overridden review returns 409 Conflict")
    void testRest_AlreadyOverridden_Returns409() throws Exception {
        AdminReviewOverrideRequest request1 = new AdminReviewOverrideRequest("First valid override reason by admin user.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk());

        AdminReviewOverrideRequest request2 = new AdminReviewOverrideRequest("Second override attempt by owner user.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("REST 9: Stale context returns 400 Bad Request")
    void testRest_StaleContext_Returns400() throws Exception {
        codeChange.setProposedHash("mutated_hash_rest");
        codeChangeRepository.save(codeChange);

        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Override attempt on drifted code change context.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("REST 10: Secret in request is redacted in HTTP response JSON")
    void testRest_SecretRedactionInResponse_RawSecretAbsentFromHttpJson() throws Exception {
        AdminReviewOverrideRequest request = new AdminReviewOverrideRequest("Override reason containing secret ghp_123456789012345678901234567890abcdef text.");

        mockMvc.perform(post("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}/override",
                        project.getId(), repository.getId(), task.getId(), review.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminOverrideReason", not(containsString("ghp_123456789012345678901234567890abcdef"))))
                .andExpect(jsonPath("$.adminOverrideReason", containsString("[REDACTED_SECRET")));
    }
}
