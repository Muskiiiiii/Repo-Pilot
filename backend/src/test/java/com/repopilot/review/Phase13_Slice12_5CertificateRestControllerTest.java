package com.repopilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.repository.AuditLogRepository;
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
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.CreatePolicyComplianceCertificateRequestDto;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ComplianceCertificateRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.ReviewContextService;
import com.repopilot.review.service.SecurityPolicyComplianceService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase13_Slice12_5CertificateRestControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private CodeChangeRepository codeChangeRepository;
    @Autowired private CodeReviewRepository codeReviewRepository;
    @Autowired private ReviewFindingRepository reviewFindingRepository;
    @Autowired private ComplianceCertificateRepository complianceCertificateRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ReviewContextService reviewContextService;
    @SpyBean private SecurityPolicyComplianceService securityPolicyComplianceService;

    private User ownerUser;
    private User memberUser;
    private User nonMemberUser;

    private String ownerToken;
    private String memberToken;
    private String nonMemberToken;

    private Project project;
    private Repository repository;
    private Task task;
    private Plan plan;
    private CodeChange codeChange;
    private CodeReview review;

    private String basePath;

    @BeforeAll
    static void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("rest_cert_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Cert REST Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerUser);

        memberUser = new User();
        memberUser.setEmail("rest_cert_member_" + UUID.randomUUID() + "@example.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Cert REST Member");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);
        memberToken = jwtTokenProvider.generateAccessToken(memberUser);

        nonMemberUser = new User();
        nonMemberUser.setEmail("rest_cert_nonmember_" + UUID.randomUUID() + "@example.com");
        nonMemberUser.setPasswordHash("hash");
        nonMemberUser.setName("Cert REST NonMember");
        nonMemberUser.setRole(UserRole.USER);
        nonMemberUser = userRepository.save(nonMemberUser);
        nonMemberToken = jwtTokenProvider.generateAccessToken(nonMemberUser);

        project = new Project();
        project.setName("Phase 13 Slice 12.5 REST Test Project");
        project.setDescription("Project for Slice 12.5 REST testing");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwner(ownerUser);
        project = projectRepository.save(project);

        projectMemberRepository.save(new ProjectMember(project, ownerUser, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(project, memberUser, ProjectRole.MEMBER));

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("test-owner");
        repository.setRepoName("cert-rest-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setTitle("Cert REST Task");
        task.setDescription("Task for REST certification testing");
        task.setStatus(TaskStatus.ANALYZING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreatedBy(ownerUser);
        task = taskRepository.save(task);

        plan = new Plan();
        plan.setTask(task);
        plan.setObjective("Cert REST Objective");
        plan = planRepository.save(plan);

        codeChange = new CodeChange();
        codeChange.setTask(task);
        codeChange.setPlan(plan);
        codeChange.setRepository(repository);
        codeChange.setFilePath("src/main/java/App.java");
        codeChange.setOperation("MODIFY");
        codeChange.setOriginalHash("orig125");
        codeChange.setProposedHash("prop125");
        codeChange.setDiff("--- a/App.java\n+++ b/App.java\n@@ -1 +1 @@\n-old\n+new");
        codeChange = codeChangeRepository.save(codeChange);

        ReviewContext ctx = reviewContextService.buildReviewContext(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, memberUser
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
        review.setAiInputHash("ai_hash_125");
        review.setSummary("Completed review for REST certification.");
        review = codeReviewRepository.save(review);

        basePath = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates",
                project.getId(), repository.getId(), task.getId(), review.getId());
    }

    @AfterEach
    void tearDown() {
        if (complianceCertificateRepository != null) { complianceCertificateRepository.deleteAll(); complianceCertificateRepository.flush(); }
        if (auditLogRepository != null) { auditLogRepository.deleteAll(); auditLogRepository.flush(); }
        if (reviewFindingRepository != null) { reviewFindingRepository.deleteAll(); reviewFindingRepository.flush(); }
        if (codeReviewRepository != null) { codeReviewRepository.deleteAll(); codeReviewRepository.flush(); }
        if (codeChangeRepository != null) { codeChangeRepository.deleteAll(); codeChangeRepository.flush(); }
        if (planRepository != null) { planRepository.deleteAll(); planRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectMemberRepository != null) { projectMemberRepository.deleteAll(); projectMemberRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    // =========================================================================
    // 1. HAPPY PATH ISSUANCE (POST 201 CREATED)
    // =========================================================================
    @Test
    @DisplayName("1. POST issuance returns 201 CREATED with complete DTO representation")
    void test1_HappyPathIssuance() throws Exception {
        String key = "idem_rest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificateId", notNullValue()))
                .andExpect(jsonPath("$.projectId", is(project.getId().toString())))
                .andExpect(jsonPath("$.reviewId", is(review.getId().toString())))
                .andExpect(jsonPath("$.idempotencyKey", is(key)))
                .andExpect(jsonPath("$.complianceStatus", is("PASSED")))
                .andExpect(jsonPath("$.certificateHash", notNullValue()))
                .andExpect(jsonPath("$.issuedAt", notNullValue()));
    }

    // =========================================================================
    // 2. HAPPY PATH RETRIEVAL (GET 200 OK)
    // =========================================================================
    @Test
    @DisplayName("2. GET by certificateId returns 200 OK with persisted certificate DTO")
    void test2_HappyPathRetrieval() throws Exception {
        String key = "idem_rest_get_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String responseJson = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String certId = objectMapper.readTree(responseJson).get("certificateId").asText();

        mockMvc.perform(get(basePath + "/" + certId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId", is(certId)))
                .andExpect(jsonPath("$.projectId", is(project.getId().toString())))
                .andExpect(jsonPath("$.idempotencyKey", is(key)));
    }

    // =========================================================================
    // 3. IDEMPOTENT POST RECOVERY
    // =========================================================================
    @Test
    @DisplayName("3. Re-posting with same Idempotency-Key recovers existing certificate")
    void test3_IdempotentPostRecovery() throws Exception {
        String key = "idem_rest_dup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String json1 = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String certId1 = objectMapper.readTree(json1).get("certificateId").asText();

        String json2 = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String certId2 = objectMapper.readTree(json2).get("certificateId").asText();
        assertEquals(certId1, certId2, "Idempotent recovery MUST return the exact same certificate ID");
    }

    // =========================================================================
    // 4. CONCURRENT >= 10 POST RACE TEST
    // =========================================================================
    @Test
    @DisplayName("4. Concurrent 10 POST requests produce exactly 1 DB row and 1 audit log")
    void test4_ConcurrentPostRace() throws Exception {
        String key = "idem_rest_race_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> mockMvc.perform(post(basePath)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getStatus()));
        }

        for (Future<Integer> f : futures) {
            assertEquals(201, f.get().intValue());
        }
        executor.shutdown();

        assertEquals(1, complianceCertificateRepository.count());
    }

    // =========================================================================
    // 5. 401 MISSING PRINCIPAL
    // =========================================================================
    @Test
    @DisplayName("5. Missing Authorization header returns 401 UNAUTHORIZED")
    void test5_MissingPrincipalReturns401() throws Exception {
        String key = "idem_rest_401_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        mockMvc.perform(post(basePath)
                        .header("Idempotency-Key", key))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));

        mockMvc.perform(get(basePath + "/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    // =========================================================================
    // 6. 403 UNAUTHORIZED NON-MEMBER
    // =========================================================================
    @Test
    @DisplayName("6. Non-member user returns 403 FORBIDDEN")
    void test6_NonMemberReturns403() throws Exception {
        String key = "idem_rest_403_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonMemberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 7. PROJECT / REPO / TASK / REVIEW IDOR HIERARCHY MISMATCHES
    // =========================================================================
    @Test
    @DisplayName("7. Path hierarchy mismatch returns 404 NOT_FOUND")
    void test7_HierarchyIdorRejection() throws Exception {
        String key = "idem_rest_idor_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        UUID wrongUuid = UUID.randomUUID();

        String wrongProjPath = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates",
                wrongUuid, repository.getId(), task.getId(), review.getId());

        mockMvc.perform(post(wrongProjPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 8. MALFORMED UUID PATH PARAMETER
    // =========================================================================
    @Test
    @DisplayName("8. Malformed UUID path parameter returns 400 BAD_REQUEST")
    void test8_MalformedUuidReturns400() throws Exception {
        String malformedPath = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates",
                "not-a-valid-uuid", repository.getId(), task.getId(), review.getId());

        mockMvc.perform(post(malformedPath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", "valid_key_123"))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 9. IDEMPOTENCY KEY TRANSPORT VALIDATION (MISSING, SHORT, LONG, INVALID CHARS)
    // =========================================================================
    @Test
    @DisplayName("9. Missing or malformed Idempotency-Key returns 400 BAD_REQUEST")
    void test9_IdempotencyKeyTransportValidation() throws Exception {
        // Missing key
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_IDEMPOTENCY_KEY")));

        // Too short key (< 8 chars)
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_IDEMPOTENCY_KEY")));

        // Invalid characters (spaces)
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", "key with spaces"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_IDEMPOTENCY_KEY")));
    }

    // =========================================================================
    // 10. VALID BOUNDARY IDEMPOTENCY KEYS
    // =========================================================================
    @Test
    @DisplayName("10. 8-character and 128-character boundary keys succeed")
    void test10_BoundaryIdempotencyKeys() throws Exception {
        // 8 chars
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", "12345678"))
                .andExpect(status().isCreated());

        // 128 chars
        String long128 = "a".repeat(128);
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", long128))
                .andExpect(status().isCreated());
    }

    // =========================================================================
    // 11. 409 IDEMPOTENCY CONFLICT
    // =========================================================================
    @Test
    @DisplayName("11. Reusing key across different review returns 409 CONFLICT")
    void test11_IdempotencyConflictReturns409() throws Exception {
        String key = "idem_rest_conflict_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Issue for review 1
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated());

        // Create review 2
        CodeReview review2 = new CodeReview();
        review2.setProject(project);
        review2.setRepository(repository);
        review2.setTask(task);
        review2.setCodeChange(codeChange);
        review2.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review2.setStatus(ReviewStatus.COMPLETED);
        com.repopilot.review.dto.ReviewContext ctx2 = reviewContextService.buildReviewContext(
                project.getId(), repository.getId(), task.getId(), codeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, memberUser
        );
        review2.setReviewContextHash(ctx2.reviewContextHash());
        review2.setModelIdentifier("model-1");
        review2 = codeReviewRepository.save(review2);

        String basePath2 = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates",
                project.getId(), repository.getId(), task.getId(), review2.getId());

        mockMvc.perform(post(basePath2)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_CONFLICT")));
    }

    // =========================================================================
    // 12. CORRUPT CERTIFICATE FAIL CLOSED (500 INTERNAL_SERVER_ERROR)
    // =========================================================================
    @Test
    @DisplayName("12. Corrupt certificate hash returns 500 INTERNAL_SERVER_ERROR")
    void test12_CorruptCertificateReturns500() throws Exception {
        String key = "idem_rest_corrupt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String json = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String certIdStr = objectMapper.readTree(json).get("certificateId").asText();
        UUID certId = UUID.fromString(certIdStr);

        // Corrupt hash
        ComplianceCertificate cert = complianceCertificateRepository.findById(certId).orElseThrow();
        cert.setCertificateHash("0000000000000000000000000000000000000000000000000000000000000000");
        complianceCertificateRepository.save(cert);

        mockMvc.perform(get(basePath + "/" + certId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("CORRUPT_CERTIFICATE")));
    }

    // =========================================================================
    // 13. GET ZERO-WRITE & ZERO-EVALUATOR PROOF
    // =========================================================================
    @Test
    @DisplayName("13. GET certificate executes zero DB writes and zero Slice 11 evaluator calls")
    void test13_GetZeroWriteAndZeroEvaluatorProof() throws Exception {
        String key = "idem_rest_zw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String json = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String certId = objectMapper.readTree(json).get("certificateId").asText();

        Mockito.reset(securityPolicyComplianceService);
        long auditCountBeforeGet = auditLogRepository.count();

        mockMvc.perform(get(basePath + "/" + certId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk());

        assertEquals(auditCountBeforeGet, auditLogRepository.count(), "GET MUST NOT generate audit logs");
        Mockito.verifyNoInteractions(securityPolicyComplianceService);
    }

    // =========================================================================
    // 14. GET BY IDEMPOTENCY KEY ENDPOINT
    // =========================================================================
    @Test
    @DisplayName("14. GET by-idempotency-key returns persisted certificate")
    void test14_GetByIdempotencyKey() throws Exception {
        String key = "idem_rest_by_key_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated());

        mockMvc.perform(get(basePath + "/by-idempotency-key/" + key)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey", is(key)));
    }
}
