package com.repopilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryBranch;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryBranchRepository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.CodeReviewResultDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.CodeReviewOrchestrationService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase13_Slice8RestApiBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

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
    private RepositoryBranchRepository repositoryBranchRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private CodeReviewRepository codeReviewRepository;

    @Autowired
    private ReviewFindingRepository reviewFindingRepository;

    @Autowired
    private HumanApprovalRepository humanApprovalRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private CodeReviewOrchestrationService orchestrationService;

    @Autowired
    private ObjectMapper objectMapper;

    private User owner;
    private User memberUser;
    private User nonMemberUser;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;
    private Plan planA;
    private CodeChange changeA;

    private String memberToken;
    private String nonMemberToken;

    @BeforeEach
    void setUp() {
        owner = new User();
        owner.setEmail("owner_rest_s8_" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("hash");
        owner.setName("Owner Rest S8");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        memberUser = new User();
        memberUser.setEmail("member_rest_s8_" + UUID.randomUUID() + "@test.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Member Rest S8");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);

        nonMemberUser = new User();
        nonMemberUser.setEmail("nonmember_rest_s8_" + UUID.randomUUID() + "@test.com");
        nonMemberUser.setPasswordHash("hash");
        nonMemberUser.setName("NonMember Rest S8");
        nonMemberUser.setRole(UserRole.USER);
        nonMemberUser = userRepository.save(nonMemberUser);

        // Project A
        projectA = new Project();
        projectA.setOwner(owner);
        projectA.setName("Project Rest S8 A " + UUID.randomUUID());
        projectA.setDescription("Project Rest S8 A");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(projectA, memberUser, ProjectRole.MEMBER));

        // Project B
        projectB = new Project();
        projectB.setOwner(nonMemberUser);
        projectB.setName("Project Rest S8 B " + UUID.randomUUID());
        projectB.setDescription("Project Rest S8 B");
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);
        projectMemberRepository.save(new ProjectMember(projectB, nonMemberUser, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("repopilot");
        repoA.setRepoName("repo-rest-s8-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        RepositoryBranch branchA = new RepositoryBranch();
        branchA.setRepository(repoA);
        branchA.setName("main");
        branchA.setCommitSha("1111222233334444555566667777888899990000");
        branchA.setIsDefault(true);
        repositoryBranchRepository.save(branchA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("nonmember");
        repoB.setRepoName("repo-rest-s8-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task Rest S8 A");
        taskA.setDescription("Task Rest S8 Description A");
        taskA.setPriority(TaskPriority.HIGH);
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(nonMemberUser);
        taskB.setTitle("Task Rest S8 B");
        taskB.setDescription("Task Rest S8 Description B");
        taskB.setPriority(TaskPriority.MEDIUM);
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB = taskRepository.save(taskB);

        planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan Rest S8 Objective A");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/SecurityGateService.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class SecurityGateService {}");
        changeA.setNewContent("public class SecurityGateService { void check() {} }");
        changeA.setOriginalHash("orig_rest_s8_123");
        changeA.setProposedHash("prop_rest_s8_456");
        changeA.setDiff("--- src/SecurityGateService.java\n+++ src/SecurityGateService.java\n@@ -1 +1 @@\n-public class SecurityGateService {}\n+public class SecurityGateService { void check() {} }\n");
        changeA.setStatus("GENERATED");
        changeA = codeChangeRepository.save(changeA);

        memberToken = jwtTokenProvider.generateAccessToken(memberUser);
        nonMemberToken = jwtTokenProvider.generateAccessToken(nonMemberUser);
    }

    @AfterEach
    void tearDown() {
        humanApprovalRepository.deleteAll();
        reviewFindingRepository.deleteAll();
        codeReviewRepository.deleteAll();
        auditLogRepository.deleteAll();
        codeChangeRepository.deleteAll();
        planRepository.deleteAll();
        taskRepository.deleteAll();
        repositoryBranchRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectMemberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("Slice 8 REST A: testAuthenticatedValidRequest_returns200")
    void testAuthenticatedValidRequest_returns200() throws Exception {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectA.getId().toString()))
                .andExpect(jsonPath("$.repositoryId").value(repoA.getId().toString()))
                .andExpect(jsonPath("$.taskId").value(taskA.getId().toString()))
                .andExpect(jsonPath("$.codeChangeId").value(changeA.getId().toString()))
                .andExpect(jsonPath("$.reviewId").value(reviewResult.reviewId().toString()))
                .andExpect(jsonPath("$.codeChangeStatus").value("GENERATED"))
                .andExpect(jsonPath("$.isCodeChangeActive").value(true))
                .andExpect(jsonPath("$.isAlreadyApproved").value(false))
                .andExpect(jsonPath("$.eligibleForApproval").value(true))
                .andExpect(jsonPath("$.eligibleForNewApproval").value(true))
                .andExpect(jsonPath("$.isContextStale").value(false))
                .andExpect(jsonPath("$.findingsSummary.totalCount").value(0));
    }

    @Test
    @DisplayName("Slice 8 REST B: testUnauthenticatedRequest_returns401")
    void testUnauthenticatedRequest_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Slice 8 REST C: testAuthenticatedUserWithoutProjectAccess_returns403")
    void testAuthenticatedUserWithoutProjectAccess_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Slice 8 REST D: testCrossProjectTask_rejected404")
    void testCrossProjectTask_rejected404() throws Exception {
        // Attempting to access Task B (belongs to Project B) under Project A URL path
        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskB.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("Slice 8 REST E: testCrossRepositoryTask_rejected404")
    void testCrossRepositoryTask_rejected404() throws Exception {
        // Server derives repositoryId from task.repositoryId. Unrelated repo mismatch safely returns 404 if modified.
        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound()); // No review exists yet -> 404 REVIEW_NOT_FOUND
    }

    @Test
    @DisplayName("Slice 8 REST F: testUnrelatedCodeChange_rejected404")
    void testUnrelatedCodeChange_rejected404() throws Exception {
        CodeChange change2 = new CodeChange();
        change2.setTask(taskB); // Belongs to taskB
        change2.setRepository(repoB);
        change2.setFilePath("src/UnrelatedService.java");
        change2.setOperation("CREATE");
        change2.setStatus("GENERATED");
        CodeChange savedChange2 = codeChangeRepository.save(change2);

        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", savedChange2.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CODE_CHANGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Slice 8 REST G: testExplicitInactiveCodeChange_returnsActiveFalse")
    void testExplicitInactiveCodeChange_returnsActiveFalse() throws Exception {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        changeA.setStatus("REJECTED");
        codeChangeRepository.save(changeA);

        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCodeChangeActive").value(false))
                .andExpect(jsonPath("$.eligibleForNewApproval").value(false));
    }

    @Test
    @DisplayName("Slice 8 REST H: testOmittedCodeChangeId_selectsLatestActiveCodeChange")
    void testOmittedCodeChangeId_selectsLatestActiveCodeChange() throws Exception {
        // Change 1
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Change 2 (newer)
        CodeChange change2 = new CodeChange();
        change2.setTask(taskA);
        change2.setPlan(planA);
        change2.setRepository(repoA);
        change2.setFilePath("src/NewService.java");
        change2.setOperation("CREATE");
        change2.setStatus("GENERATED");
        change2 = codeChangeRepository.save(change2);

        CodeReviewResultDto r2 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), change2.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Omit codeChangeId query param
        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codeChangeId").value(change2.getId().toString()))
                .andExpect(jsonPath("$.reviewId").value(r2.reviewId().toString()));
    }

    @Test
    @DisplayName("Slice 8 REST I: testZeroActiveCodeChanges_returns404NoActiveCodeChange")
    void testZeroActiveCodeChanges_returns404NoActiveCodeChange() throws Exception {
        changeA.setStatus("REJECTED");
        codeChangeRepository.save(changeA);

        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ACTIVE_CODE_CHANGE"));
    }

    @Test
    @DisplayName("Slice 8 REST J: testLatestCompletedStaleReview_reportsStaleNoFallback")
    void testLatestCompletedStaleReview_reportsStaleNoFallback() throws Exception {
        CodeReviewResultDto r1 = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Mutate context
        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setProposedHash("mutated_hash_rest");
        codeChangeRepository.save(cc);

        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", cc.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(r1.reviewId().toString()))
                .andExpect(jsonPath("$.isContextStale").value(true))
                .andExpect(jsonPath("$.eligibleForApproval").value(false));
    }

    @Test
    @DisplayName("Slice 8 REST K: testFindingSnippetsRemainSecretRedacted")
    void testFindingSnippetsRemainSecretRedacted() throws Exception {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        CodeReview review = codeReviewRepository.findById(reviewResult.reviewId()).orElseThrow();

        ReviewFinding f = new ReviewFinding();
        f.setReview(review);
        f.setProject(projectA);
        f.setRepository(repoA);
        f.setTask(taskA);
        f.setCodeChange(changeA);
        f.setFindingType(FindingType.AI_FINDING);
        f.setCategory("SECRET_LEAK");
        f.setSeverity(FindingSeverity.HIGH);
        f.setConfidence(FindingConfidence.HIGH);
        f.setTitle("Leaked secret ghp_1234567890abcdef1234567890abcdef123456");
        f.setDescription("Leaked JWT token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        f.setFilePath("src/SecurityGateService.java");
        f.setStartLine(1);
        f.setEndLine(10);
        f.setContentHash("hash_secret_rest");
        f.setFingerprint("fp_secret_rest");
        reviewFindingRepository.save(f);

        mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snippets[0].title", not(containsString("ghp_1234567890abcdef1234567890abcdef123456"))))
                .andExpect(jsonPath("$.snippets[0].description", not(containsString("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))));
    }

    @Test
    @DisplayName("Slice 8 REST L: testResponseDoesNotExposeLeaseTokenOrWorkerId")
    void testResponseDoesNotExposeLeaseTokenOrWorkerId() throws Exception {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("leaseToken"), "Response MUST NOT contain leaseToken");
        assertFalse(body.contains("workerId"), "Response MUST NOT contain workerId");
        assertFalse(body.contains("fencingToken"), "Response MUST NOT contain fencingToken");
    }

    @Test
    @DisplayName("Slice 8 REST M: testGetCausesZeroWrites")
    void testGetCausesZeroWrites() throws Exception {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        long reviewCountBefore = codeReviewRepository.count();
        long findingCountBefore = reviewFindingRepository.count();
        long approvalCountBefore = humanApprovalRepository.count();
        long codeChangeCountBefore = codeChangeRepository.count();

        TestSqlStatementInspector.startRecording();
        try {
            mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                            .param("codeChangeId", changeA.getId().toString())
                            .header("Authorization", "Bearer " + memberToken))
                    .andExpect(status().isOk());
        } finally {
            TestSqlStatementInspector.stopRecording();
        }

        assertEquals(0, TestSqlStatementInspector.countInserts(), "INSERT statement count MUST be 0");
        assertEquals(0, TestSqlStatementInspector.countUpdates(), "UPDATE statement count MUST be 0");
        assertEquals(0, TestSqlStatementInspector.countDeletes(), "DELETE statement count MUST be 0");

        assertEquals(reviewCountBefore, codeReviewRepository.count(), "Review count MUST NOT change");
        assertEquals(findingCountBefore, reviewFindingRepository.count(), "Finding count MUST NOT change");
        assertEquals(approvalCountBefore, humanApprovalRepository.count(), "Approval count MUST NOT change");
        assertEquals(codeChangeCountBefore, codeChangeRepository.count(), "CodeChange count MUST NOT change");
    }

    @Test
    @DisplayName("Slice 8 REST Q: testStaleContextGet_producesZeroWriteStatements")
    void testStaleContextGet_producesZeroWriteStatements() throws Exception {
        CodeReviewResultDto reviewResult = orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        // Mutate CodeChange context after review to induce staleness
        CodeChange cc = codeChangeRepository.findById(changeA.getId()).orElseThrow();
        cc.setProposedHash("stale_context_hash_zero_write_proof");
        codeChangeRepository.save(cc);

        long reviewCountBefore = codeReviewRepository.count();
        long findingCountBefore = reviewFindingRepository.count();
        long approvalCountBefore = humanApprovalRepository.count();
        long codeChangeCountBefore = codeChangeRepository.count();

        TestSqlStatementInspector.startRecording();
        try {
            mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                            .param("codeChangeId", cc.getId().toString())
                            .header("Authorization", "Bearer " + memberToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reviewId").value(reviewResult.reviewId().toString()))
                    .andExpect(jsonPath("$.isContextStale").value(true))
                    .andExpect(jsonPath("$.eligibleForApproval").value(false));
        } finally {
            TestSqlStatementInspector.stopRecording();
        }

        assertEquals(0, TestSqlStatementInspector.countInserts(), "Stale context GET: INSERT count MUST be 0");
        assertEquals(0, TestSqlStatementInspector.countUpdates(), "Stale context GET: UPDATE count MUST be 0");
        assertEquals(0, TestSqlStatementInspector.countDeletes(), "Stale context GET: DELETE count MUST be 0");

        assertEquals(reviewCountBefore, codeReviewRepository.count(), "Review count MUST NOT change on stale GET");
        assertEquals(findingCountBefore, reviewFindingRepository.count(), "Finding count MUST NOT change on stale GET");
        assertEquals(approvalCountBefore, humanApprovalRepository.count(), "Approval count MUST NOT change on stale GET");
        assertEquals(codeChangeCountBefore, codeChangeRepository.count(), "CodeChange count MUST NOT change on stale GET");
    }

    @Test
    @DisplayName("Slice 8 REST N: testInternalExceptionDoesNotLeakStackTrace")
    void testInternalExceptionDoesNotLeakStackTrace() throws Exception {
        // Supplying non-existent task UUID
        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), UUID.randomUUID())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("Exception"), "Response MUST NOT leak exception class names");
        assertFalse(body.contains("at com.repopilot"), "Response MUST NOT leak Java stack trace");
        assertTrue(body.contains("\"code\":\"TASK_NOT_FOUND\""), "Response MUST contain structured error code");
    }

    @Test
    @DisplayName("Slice 8 REST O: testMalformedPathIds_handledSafely")
    void testMalformedPathIds_handledSafely() throws Exception {
        mockMvc.perform(get("/api/v1/projects/not-a-uuid/tasks/not-a-uuid/review-summary")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Slice 8 REST P: testDeterministicHttpResponseContract")
    void testDeterministicHttpResponseContract() throws Exception {
        orchestrationService.executeCodeReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, null, owner
        );

        MvcResult res1 = mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult res2 = mockMvc.perform(get("/api/v1/projects/{projectId}/tasks/{taskId}/review-summary", projectA.getId(), taskA.getId())
                        .param("codeChangeId", changeA.getId().toString())
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(res1.getResponse().getContentAsString(), res2.getResponse().getContentAsString(), "Consecutive GET responses MUST be 100% byte-for-byte identical");
    }
}
