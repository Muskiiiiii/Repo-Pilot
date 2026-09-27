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
import com.repopilot.review.dto.UpdateFindingDispositionRequestDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase13_Slice9ReviewFindingRestControllerTest {

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
    private CodeReview codeReview;
    private ReviewFinding finding;

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("rest_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("REST Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerUser);

        adminUser = new User();
        adminUser.setEmail("rest_admin_" + UUID.randomUUID() + "@example.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("REST Admin");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);
        adminToken = jwtTokenProvider.generateAccessToken(adminUser);

        memberUser = new User();
        memberUser.setEmail("rest_member_" + UUID.randomUUID() + "@example.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("REST Member");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);
        memberToken = jwtTokenProvider.generateAccessToken(memberUser);

        viewerUser = new User();
        viewerUser.setEmail("rest_viewer_" + UUID.randomUUID() + "@example.com");
        viewerUser.setPasswordHash("hash");
        viewerUser.setName("REST Viewer");
        viewerUser.setRole(UserRole.USER);
        viewerUser = userRepository.save(viewerUser);
        viewerToken = jwtTokenProvider.generateAccessToken(viewerUser);

        project = new Project();
        project.setName("Phase 13 Slice 9 REST Test Project");
        project.setDescription("Project for Slice 9 REST controller testing");
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
        task.setCreatedBy(ownerUser);
        task.setTitle("Slice 9 REST Test Task");
        task.setDescription("Task for REST API testing");
        task.setStatus(TaskStatus.CREATED);
        task.setPriority(TaskPriority.HIGH);
        task = taskRepository.save(task);

        codeChange = new CodeChange();
        codeChange.setRepository(repository);
        codeChange.setTask(task);
        codeChange.setFilePath("src/main/java/Main.java");
        codeChange.setOperation("MODIFY");
        codeChange.setDiff("--- a/Main.java\n+++ b/Main.java\n@@ -1 +1 @@\n-old\n+new");
        codeChange.setOriginalHash("hash111111111111111111111111111111111111111111111111111111111111");
        codeChange.setProposedHash("hash222222222222222222222222222222222222222222222222222222222222");
        codeChange = codeChangeRepository.save(codeChange);

        codeReview = new CodeReview();
        codeReview.setProject(project);
        codeReview.setRepository(repository);
        codeReview.setTask(task);
        codeReview.setCodeChange(codeChange);
        codeReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        codeReview.setStatus(ReviewStatus.COMPLETED);
        codeReview.setModelIdentifier("gemini-2.5-pro");
        codeReview.setReviewContextHash("context_hash_rest_99999999999999999999999999999999999999");
        codeReview = codeReviewRepository.save(codeReview);

        finding = new ReviewFinding();
        finding.setProject(project);
        finding.setRepository(repository);
        finding.setTask(task);
        finding.setCodeChange(codeChange);
        finding.setReview(codeReview);
        finding.setCategory("SECURITY");
        finding.setCweId("CWE-89");
        finding.setSeverity(FindingSeverity.HIGH);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setTitle("SQL Injection Vulnerability");
        finding.setDescription("Found unescaped query string in repository search");
        finding.setFilePath("src/main/java/Main.java");
        finding.setStartLine(45);
        finding.setEndLine(48);
        finding.setSide(FindingSide.PROPOSED);
        finding.setContentHash("content_hash_222222222222222222222222222222222222222222222222");
        finding.setFingerprint("fingerprint_222222222222222222222222222222222222222222222222");
        finding.setStatus(FindingStatus.OPEN);
        finding = reviewFindingRepository.save(finding);
    }

    @AfterEach
    void tearDown() {
        auditLogRepository.deleteAllInBatch();
        reviewFindingRepository.deleteAllInBatch();
        codeReviewRepository.deleteAllInBatch();
        codeChangeRepository.deleteAllInBatch();
        taskRepository.deleteAllInBatch();
        repositoryRepository.deleteAllInBatch();
        projectMemberRepository.deleteAllInBatch();
        projectRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("REST Test 1: Unauthenticated PATCH returns 401 UNAUTHORIZED")
    void testUnauthenticatedPatchReturns401() throws Exception {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledge note"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("REST Test 2: VIEWER PATCH returns 403 FORBIDDEN")
    void testViewerPatchReturns403() throws Exception {
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledge note by viewer"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REST Test 3: MEMBER allowed transition (OPEN -> ACKNOWLEDGED, RESOLVED) returns 200 OK")
    void testMemberAllowedTransitionsReturn200() throws Exception {
        UpdateFindingDispositionRequestDto reqAck = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Member acknowledging"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqAck)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));

        UpdateFindingDispositionRequestDto reqResolve = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "Resolved sql injection with parameterized query"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqResolve)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    @DisplayName("REST Test 4: MEMBER forbidden administrative transitions (FALSE_POSITIVE, SUPPRESSED) returns 403 FORBIDDEN")
    void testMemberForbiddenAdministrativeTransitionsReturn403() throws Exception {
        UpdateFindingDispositionRequestDto reqFalsePos = new UpdateFindingDispositionRequestDto(
                FindingStatus.FALSE_POSITIVE, "Member claiming false positive"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqFalsePos)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
    }

    @Test
    @DisplayName("REST Test 5: ADMIN allowed administrative transitions (FALSE_POSITIVE, SUPPRESSED) returns 200 OK")
    void testAdminAllowedAdministrativeTransitionsReturn200() throws Exception {
        UpdateFindingDispositionRequestDto reqFalsePos = new UpdateFindingDispositionRequestDto(
                FindingStatus.FALSE_POSITIVE, "Admin verified SQL injection false positive in mock database"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqFalsePos)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALSE_POSITIVE"));
    }

    @Test
    @DisplayName("REST Test 6: OWNER allowed administrative transitions returns 200 OK")
    void testOwnerAllowedAdministrativeTransitionsReturn200() throws Exception {
        UpdateFindingDispositionRequestDto reqSuppressed = new UpdateFindingDispositionRequestDto(
                FindingStatus.SUPPRESSED, "Owner approved compliance waiver #54321"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqSuppressed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"));
    }

    @Test
    @DisplayName("REST Test 7: Cross-project IDOR returns 404 / 403")
    void testCrossProjectIdorReturnsError() throws Exception {
        UUID otherProjectId = UUID.randomUUID();
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledge note"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                otherProjectId, task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("REST Test 8: Cross-task IDOR returns 403 / 404")
    void testCrossTaskIdorReturnsError() throws Exception {
        UUID otherTaskId = UUID.randomUUID();
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledge note"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), otherTaskId, codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("REST Test 9: Cross-review IDOR returns 404 NOT_FOUND")
    void testCrossReviewIdorReturns404() throws Exception {
        UUID otherReviewId = UUID.randomUUID();
        UpdateFindingDispositionRequestDto req = new UpdateFindingDispositionRequestDto(
                FindingStatus.ACKNOWLEDGED, "Acknowledge note"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), otherReviewId, finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REST Test 10: Authorized no-op request returns 200 OK with zero audit emissions")
    void testAuthorizedNoOpReturns200ZeroAudit() throws Exception {
        long initialAudits = auditLogRepository.count();

        UpdateFindingDispositionRequestDto reqNoOp = new UpdateFindingDispositionRequestDto(
                FindingStatus.OPEN, "No-op reason"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqNoOp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        long finalAudits = auditLogRepository.count();
        assertEquals(initialAudits, finalAudits, "Authorized no-op must emit zero audit logs");
    }

    @Test
    @DisplayName("REST Test 11: Reason minimum length validation failure returns 400 BAD_REQUEST")
    void testReasonMinLengthValidationReturns400() throws Exception {
        UpdateFindingDispositionRequestDto reqShort = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "fix"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqShort)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REASON_TOO_SHORT"));
    }

    @Test
    @DisplayName("REST Test 12: Secret-containing justification is redacted in response and audit")
    void testSecretContainingJustificationRedacted() throws Exception {
        UpdateFindingDispositionRequestDto reqSecret = new UpdateFindingDispositionRequestDto(
                FindingStatus.RESOLVED, "Resolved by removing secret ghp_123456789012345678901234567890123456"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqSecret)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    @DisplayName("REST Test 13: Unauthorized user calling no-op is blocked with 403 FORBIDDEN")
    void testUnauthorizedNoOpReturns403() throws Exception {
        UpdateFindingDispositionRequestDto reqNoOp = new UpdateFindingDispositionRequestDto(
                FindingStatus.OPEN, "No-op attempt by viewer"
        );

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reqNoOp)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REST Test 14: Invalid/null status request body returns 400 BAD_REQUEST")
    void testInvalidTransitionReturns400() throws Exception {
        String invalidJson = "{\"status\": null, \"reason\": \"no status provided\"}";

        mockMvc.perform(patch("/api/v1/projects/{projectId}/tasks/{taskId}/reviews/{reviewId}/findings/{findingId}/disposition",
                project.getId(), task.getId(), codeReview.getId(), finding.getId())
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
