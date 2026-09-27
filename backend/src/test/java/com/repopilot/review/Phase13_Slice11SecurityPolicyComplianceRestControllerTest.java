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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase13_Slice11SecurityPolicyComplianceRestControllerTest {

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
    private User nonMemberUser;

    private String ownerToken;
    private String adminToken;
    private String memberToken;
    private String viewerToken;
    private String nonMemberToken;

    private Project project;
    private Repository repository;
    private Task task;
    private CodeChange codeChange;
    private CodeReview review;

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("rest_s11_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("REST S11 Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerUser);

        adminUser = new User();
        adminUser.setEmail("rest_s11_admin_" + UUID.randomUUID() + "@example.com");
        adminUser.setPasswordHash("hash");
        adminUser.setName("REST S11 Admin");
        adminUser.setRole(UserRole.USER);
        adminUser = userRepository.save(adminUser);
        adminToken = jwtTokenProvider.generateAccessToken(adminUser);

        memberUser = new User();
        memberUser.setEmail("rest_s11_member_" + UUID.randomUUID() + "@example.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("REST S11 Member");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);
        memberToken = jwtTokenProvider.generateAccessToken(memberUser);

        viewerUser = new User();
        viewerUser.setEmail("rest_s11_viewer_" + UUID.randomUUID() + "@example.com");
        viewerUser.setPasswordHash("hash");
        viewerUser.setName("REST S11 Viewer");
        viewerUser.setRole(UserRole.USER);
        viewerUser = userRepository.save(viewerUser);
        viewerToken = jwtTokenProvider.generateAccessToken(viewerUser);

        nonMemberUser = new User();
        nonMemberUser.setEmail("rest_s11_nonmember_" + UUID.randomUUID() + "@example.com");
        nonMemberUser.setPasswordHash("hash");
        nonMemberUser.setName("REST S11 NonMember");
        nonMemberUser.setRole(UserRole.USER);
        nonMemberUser = userRepository.save(nonMemberUser);
        nonMemberToken = jwtTokenProvider.generateAccessToken(nonMemberUser);

        project = new Project();
        project.setName("Phase 13 Slice 11 REST Test Project");
        project.setDescription("Project for Slice 11 REST controller testing");
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
        repository.setRepoName("test-s11-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setTitle("REST Slice 11 Task");
        task.setDescription("Task for Slice 11 REST testing");
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
        review.setAiInputHash("ai_input_hash_rest_s11");
        review.setPolicyVersion("2026.1");
        review.setValidatorVersion("v1.0.0");
        review = codeReviewRepository.save(review);
    }

    @AfterEach
    void tearDown() {
        if (reviewFindingRepository != null) reviewFindingRepository.deleteAll();
        if (codeReviewRepository != null) codeReviewRepository.deleteAll();
        if (codeChangeRepository != null) codeChangeRepository.deleteAll();
        if (taskRepository != null) taskRepository.deleteAll();
        if (repositoryRepository != null) repositoryRepository.deleteAll();
        if (projectMemberRepository != null) projectMemberRepository.deleteAll();
        if (projectRepository != null) projectRepository.deleteAll();
        if (auditLogRepository != null) auditLogRepository.deleteAll();
        if (userRepository != null) userRepository.deleteAll();
    }

    private String getEndpoint(UUID pId, UUID rId, UUID tId, UUID revId) {
        return String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/policy-compliance",
                pId, rId, tId, revId);
    }

    @Test
    @DisplayName("REST 1: Authenticated VIEWER retrieves policy compliance (200 OK, PASSED)")
    void testGetPolicyCompliance_Success_Viewer() throws Exception {
        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(review.getId().toString()))
                .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
                .andExpect(jsonPath("$.repositoryId").value(repository.getId().toString()))
                .andExpect(jsonPath("$.taskId").value(task.getId().toString()))
                .andExpect(jsonPath("$.complianceStatus").value("PASSED"))
                .andExpect(jsonPath("$.criticalUnmitigatedCount").value(0))
                .andExpect(jsonPath("$.highUnmitigatedCount").value(0))
                .andExpect(jsonPath("$.isExempted").value(false))
                .andExpect(jsonPath("$.policyViolations").isArray());
    }

    @Test
    @DisplayName("REST 2: Authenticated MEMBER / ADMIN / OWNER all succeed (200 OK)")
    void testGetPolicyCompliance_Success_AllRoles() throws Exception {
        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("REST 3: Unauthenticated request returns 401 UNAUTHORIZED")
    void testGetPolicyCompliance_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("REST 4: Non-member user returns 403 FORBIDDEN")
    void testGetPolicyCompliance_NonMember_Returns403() throws Exception {
        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("REST 5: Mismatched projectId returns 404 NOT_FOUND")
    void testGetPolicyCompliance_ProjectIdMismatch_Returns404() throws Exception {
        UUID randomProjId = UUID.randomUUID();
        mockMvc.perform(get(getEndpoint(randomProjId, repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REST 6: Mismatched repositoryId returns 404 NOT_FOUND")
    void testGetPolicyCompliance_RepositoryIdMismatch_Returns404() throws Exception {
        UUID randomRepoId = UUID.randomUUID();
        mockMvc.perform(get(getEndpoint(project.getId(), randomRepoId, task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REST 7: Mismatched taskId returns 404 NOT_FOUND")
    void testGetPolicyCompliance_TaskIdMismatch_Returns404() throws Exception {
        UUID randomTaskId = UUID.randomUUID();
        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), randomTaskId, review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REST 8: Mismatched reviewId returns 404 NOT_FOUND")
    void testGetPolicyCompliance_ReviewIdMismatch_Returns404() throws Exception {
        UUID randomReviewId = UUID.randomUUID();
        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), randomReviewId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("REST 9: Non-completed review status returns 400 BAD_REQUEST")
    void testGetPolicyCompliance_NonCompletedReview_Returns400() throws Exception {
        review.setStatus(ReviewStatus.IN_PROGRESS);
        codeReviewRepository.save(review);

        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REVIEW_STATUS"));
    }

    @Test
    @DisplayName("REST 10: Stale context hash returns 400 BAD_REQUEST")
    void testGetPolicyCompliance_StaleContext_Returns400() throws Exception {
        review.setReviewContextHash("stale_hash_value");
        codeReviewRepository.save(review);

        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STALE_CONTEXT"));
    }

    @Test
    @DisplayName("REST 11: Unmitigated CRITICAL finding yields FAILED status")
    void testGetPolicyCompliance_UnmitigatedCritical_ReturnsFailed() throws Exception {
        ReviewFinding finding = new ReviewFinding();
        finding.setReview(review);
        finding.setCodeChange(codeChange);
        finding.setProject(project);
        finding.setRepository(repository);
        finding.setTask(task);
        finding.setFindingType(FindingType.AI_FINDING);
        finding.setCategory("SECURITY");
        finding.setContentHash("ch_" + UUID.randomUUID());
        finding.setFingerprint("fp_" + UUID.randomUUID());
        finding.setTitle("SQL Injection");
        finding.setDescription("Raw query concatenation secret=sk-1234567890abcdef1234567890abcdef");
        finding.setSeverity(FindingSeverity.CRITICAL);
        finding.setStatus(FindingStatus.OPEN);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setCweId("CWE-89");
        finding.setFilePath("src/main/java/Main.java");
        finding.setStartLine(10);
        finding.setEndLine(15);
        reviewFindingRepository.save(finding);

        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complianceStatus").value("FAILED"))
                .andExpect(jsonPath("$.criticalUnmitigatedCount").value(1))
                .andExpect(jsonPath("$.policyViolations[0].cweId").value("CWE-89"))
                .andExpect(jsonPath("$.policyViolations[0].description", containsString("[REDACTED_SECRET_PRE_LLM]")));
    }

    @Test
    @DisplayName("REST 12: Slice 10 Admin Override yields EXEMPTED status")
    void testGetPolicyCompliance_AdminOverride_ReturnsExempted() throws Exception {
        review.setAdminOverrideReason("Approved administrative security exemption justification for release.");
        review.setAdminOverrideByUser(adminUser);
        review.setAdminOverrideAt(Instant.now());
        codeReviewRepository.save(review);

        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complianceStatus").value("EXEMPTED"))
                .andExpect(jsonPath("$.isExempted").value(true))
                .andExpect(jsonPath("$.adminOverrideReason").value("Approved administrative security exemption justification for release."))
                .andExpect(jsonPath("$.adminOverrideByUserId").value(adminUser.getId().toString()));
    }

    @Test
    @DisplayName("REST 13: Read-only request creates ZERO audit log entries")
    void testGetPolicyCompliance_ZeroAuditLogEntries() throws Exception {
        long initialAuditCount = auditLogRepository.count();

        mockMvc.perform(get(getEndpoint(project.getId(), repository.getId(), task.getId(), review.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken))
                .andExpect(status().isOk());

        long finalAuditCount = auditLogRepository.count();
        assertEquals(initialAuditCount, finalAuditCount, "Policy compliance read-only GET endpoint must create 0 audit log entries");
    }
}
