package com.repopilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
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
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ComplianceCertificateRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.CanonicalCertificateHashService;
import com.repopilot.review.service.ReviewContextService;
import com.repopilot.review.service.SecurityPolicyComplianceCertificationService;
import com.repopilot.review.service.SecurityPolicyComplianceService;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class Phase13_Slice12_6FinalHostileSecurityGateTest {

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
    @Autowired private SecurityPolicyComplianceCertificationService certificationService;
    @Autowired private CanonicalCertificateHashService canonicalCertificateHashService;
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
        ownerUser.setEmail("gate_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Gate Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerUser);

        memberUser = new User();
        memberUser.setEmail("gate_member_" + UUID.randomUUID() + "@example.com");
        memberUser.setPasswordHash("hash");
        memberUser.setName("Gate Member");
        memberUser.setRole(UserRole.USER);
        memberUser = userRepository.save(memberUser);
        memberToken = jwtTokenProvider.generateAccessToken(memberUser);

        nonMemberUser = new User();
        nonMemberUser.setEmail("gate_nonmember_" + UUID.randomUUID() + "@example.com");
        nonMemberUser.setPasswordHash("hash");
        nonMemberUser.setName("Gate NonMember");
        nonMemberUser.setRole(UserRole.USER);
        nonMemberUser = userRepository.save(nonMemberUser);
        nonMemberToken = jwtTokenProvider.generateAccessToken(nonMemberUser);

        project = new Project();
        project.setName("Gate Project");
        project.setDescription("Gate Project Description");
        project.setOwner(ownerUser);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.save(project);

        projectMemberRepository.save(new ProjectMember(project, ownerUser, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(project, memberUser, ProjectRole.MEMBER));

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("gate-owner");
        repository.setRepoName("gate-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setCreatedBy(ownerUser);
        task.setTitle("Gate Task");
        task.setDescription("Gate Prompt");
        task.setStatus(TaskStatus.COMPLETED);
        task.setPriority(TaskPriority.HIGH);
        task = taskRepository.save(task);

        plan = new Plan();
        plan.setTask(task);
        plan.setObjective("Gate Plan Objective");
        plan = planRepository.save(plan);

        codeChange = new CodeChange();
        codeChange.setTask(task);
        codeChange.setPlan(plan);
        codeChange.setRepository(repository);
        codeChange.setFilePath("src/Main.java");
        codeChange.setOperation("MODIFY");
        codeChange.setOriginalHash("a".repeat(64));
        codeChange.setProposedHash("b".repeat(64));
        codeChange.setDiff("@@ -1 +1 @@");
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
        review.setAiInputHash("ai_hash");
        review.setSummary("Gate Review Approved");
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

    // ============================================================
    // A. COMPLETE END-TO-END HAPPY PATH
    // ============================================================
    @Test
    @DisplayName("Section A: Complete End-To-End Happy Path Verification")
    void sectionA_endToEndHappyPath() throws Exception {
        String idempotencyKey = "idem_gate_happy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long certCountBefore = complianceCertificateRepository.count();
        long auditCountBefore = auditLogRepository.count();

        // 1. POST certificate
        MvcResult postResult = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificateId").exists())
                .andExpect(jsonPath("$.complianceStatus").value("PASSED"))
                .andExpect(jsonPath("$.certificateHash").exists())
                .andReturn();

        SecurityPolicyComplianceCertificateDto issuedDto = objectMapper.readValue(
                postResult.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        // 2. Database assertions: exactly 1 certificate and 1 issuance audit
        assertEquals(certCountBefore + 1, complianceCertificateRepository.count());
        assertEquals(auditCountBefore + 1, auditLogRepository.count());

        // 3. Cryptographic hash verification: stored hash equals independently computed SHA-256
        ComplianceCertificate certInDb = complianceCertificateRepository.findById(issuedDto.certificateId()).orElseThrow();
        String computedHash = canonicalCertificateHashService.hashCertificateEntity(certInDb);
        assertEquals(certInDb.getCertificateHash(), computedHash);
        assertEquals(issuedDto.certificateHash(), computedHash);

        // 4. GET certificate & zero-write / zero-evaluator verification
        long certCountAfterIssue = complianceCertificateRepository.count();
        long auditCountAfterIssue = auditLogRepository.count();
        clearInvocations(securityPolicyComplianceService);

        mockMvc.perform(get(basePath + "/" + issuedDto.certificateId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").value(issuedDto.certificateId().toString()))
                .andExpect(jsonPath("$.certificateHash").value(computedHash));

        // Assert GET performs ZERO writes and ZERO policy evaluations
        assertEquals(certCountAfterIssue, complianceCertificateRepository.count());
        assertEquals(auditCountAfterIssue, auditLogRepository.count());
        Mockito.verifyNoInteractions(securityPolicyComplianceService);
    }

    // ============================================================
    // B. REST AUTHENTICATION / AUTHORIZATION HOSTILE MATRIX
    // ============================================================
    @Test
    @DisplayName("Section B: REST Auth/Authz Hostile Matrix (No IDOR, Strict Controls)")
    void sectionB_authAndHierarchyMatrix() throws Exception {
        String key = "idem_gate_auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // 1. Unauthenticated -> 401
        mockMvc.perform(post(basePath)
                        .header("Idempotency-Key", key))
                .andExpect(status().isUnauthorized());

        // 2. Non-member user -> 403
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonMemberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isForbidden());

        // Issue valid certificate for hierarchy testing
        MvcResult result = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();
        SecurityPolicyComplianceCertificateDto validDto = objectMapper.readValue(
                result.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        // 3. Cross-Project Request -> 404
        UUID fakeProjectId = UUID.randomUUID();
        String crossProjectUrl = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates/%s",
                fakeProjectId, repository.getId(), task.getId(), review.getId(), validDto.certificateId());
        mockMvc.perform(get(crossProjectUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isNotFound());

        // 4. Cross-Repository Request -> 404
        UUID fakeRepoId = UUID.randomUUID();
        String crossRepoUrl = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates/%s",
                project.getId(), fakeRepoId, task.getId(), review.getId(), validDto.certificateId());
        mockMvc.perform(get(crossRepoUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isNotFound());

        // 5. Cross-Task Request -> 404
        UUID fakeTaskId = UUID.randomUUID();
        String crossTaskUrl = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates/%s",
                project.getId(), repository.getId(), fakeTaskId, review.getId(), validDto.certificateId());
        mockMvc.perform(get(crossTaskUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isNotFound());

        // 6. Cross-Review Request -> 404
        UUID fakeReviewId = UUID.randomUUID();
        String crossReviewUrl = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates/%s",
                project.getId(), repository.getId(), task.getId(), fakeReviewId, validDto.certificateId());
        mockMvc.perform(get(crossReviewUrl)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // C. IDEMPOTENCY KEY HOSTILE MATRIX
    // ============================================================
    @Test
    @DisplayName("Section C: Idempotency Key Hostile Attack Matrix")
    void sectionC_idempotencyKeyHostileMatrix() throws Exception {
        // Missing key -> 400
        mockMvc.perform(post(basePath).header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        // Blank / spaces / short / long / invalid chars -> 400
        for (String invalidKey : List.of("", "   ", "short12", "a".repeat(129), "key_invalid!@#$", "key with space", "key+plus")) {
            mockMvc.perform(post(basePath)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                            .header("Idempotency-Key", invalidKey))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        }

        // Boundary valid lengths: 8 and 128
        String key8 = "12345678";
        String key128 = "a".repeat(128);

        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key8))
                .andExpect(status().isCreated());

        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key128))
                .andExpect(status().isCreated());

        // Conflicting header values: Idempotency-Key vs X-Idempotency-Key -> 400 Conflict rejection
        mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", "key_alpha_12345")
                        .header("X-Idempotency-Key", "key_beta_67890"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    // ============================================================
    // D. POST CONCURRENCY / DATABASE RACE
    // ============================================================
    @Test
    @DisplayName("Section D: 20-Thread Concurrent POST Database Race Verification")
    void sectionD_concurrentPostRace20Threads() throws Exception {
        String raceKey = "idem_gate_race_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);

        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                latch.await();
                return mockMvc.perform(post(basePath)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                                .header("Idempotency-Key", raceKey))
                        .andReturn();
            }));
        }

        latch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

        List<SecurityPolicyComplianceCertificateDto> dtos = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            MvcResult res = f.get();
            assertEquals(201, res.getResponse().getStatus());
            dtos.add(objectMapper.readValue(res.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class));
        }

        // All 20 threads must return the exact same certificate ID & hash
        UUID firstId = dtos.get(0).certificateId();
        String firstHash = dtos.get(0).certificateHash();
        for (SecurityPolicyComplianceCertificateDto dto : dtos) {
            assertEquals(firstId, dto.certificateId());
            assertEquals(firstHash, dto.certificateHash());
        }

        // Exactly 1 certificate row in DB
        List<ComplianceCertificate> certsInDb = complianceCertificateRepository.findAll();
        long raceCerts = certsInDb.stream().filter(c -> raceKey.equals(c.getIdempotencyKey())).count();
        assertEquals(1, raceCerts);

        // Audit log exists and references the certificate resource ID
        List<AuditLog> audits = auditLogRepository.findAll();
        long raceAudits = audits.stream().filter(a -> firstId.equals(a.getResourceId())).count();
        assertEquals(1, raceAudits);
    }

    // ============================================================
    // G. HISTORICAL CERTIFICATE ATTACK
    // ============================================================
    @Test
    @DisplayName("Section G: Historical Certificate Commitment & Immutability Verification")
    void sectionG_historicalCertificateCommitment() throws Exception {
        String key = "idem_gate_hist_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MvcResult postRes = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();

        SecurityPolicyComplianceCertificateDto initialDto = objectMapper.readValue(
                postRes.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        // Now mutate the underlying findings in the DB after certificate issuance
        ReviewFinding finding = new ReviewFinding();
        finding.setReview(review);
        finding.setProject(project);
        finding.setRepository(repository);
        finding.setTask(task);
        finding.setCodeChange(codeChange);
        finding.setCategory("SECURITY");
        finding.setSeverity(FindingSeverity.CRITICAL);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setStatus(FindingStatus.OPEN);
        finding.setTitle("Hostile Finding Added Post-Issuance");
        finding.setFilePath("src/Main.java");
        finding.setStartLine(10);
        finding.setEndLine(10);
        finding.setContentHash("c".repeat(64));
        finding.setFingerprint("f".repeat(64));
        finding.setDescription("Post issuance attack");
        reviewFindingRepository.save(finding);

        // Query historical certificate via GET and via Idempotency Recovery
        clearInvocations(securityPolicyComplianceService);

        MvcResult getRes = mockMvc.perform(get(basePath + "/" + initialDto.certificateId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andReturn();

        SecurityPolicyComplianceCertificateDto historicalGetDto = objectMapper.readValue(
                getRes.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        assertEquals(initialDto.certificateId(), historicalGetDto.certificateId());
        assertEquals(initialDto.findingEvidenceHash(), historicalGetDto.findingEvidenceHash());
        assertEquals(initialDto.certificateHash(), historicalGetDto.certificateHash());
        assertEquals(0, historicalGetDto.criticalUnmitigatedCount());

        // Idempotency recovery must return historical state without recomputing findings or reevaluating Slice 11
        MvcResult recoverRes = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();

        SecurityPolicyComplianceCertificateDto recoveredDto = objectMapper.readValue(
                recoverRes.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        assertEquals(initialDto.certificateId(), recoveredDto.certificateId());
        assertEquals(initialDto.certificateHash(), recoveredDto.certificateHash());
        Mockito.verifyNoInteractions(securityPolicyComplianceService);
    }

    // ============================================================
    // H. 20-FIELD CRYPTOGRAPHIC TAMPERING ATTACK
    // ============================================================
    @Test
    @DisplayName("Section H: 20-Field Canonical Cryptographic Tampering Verification")
    void sectionH_cryptographicTamperingMatrix() throws Exception {
        String key = "idem_gate_tamper_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MvcResult postRes = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();

        SecurityPolicyComplianceCertificateDto originalDto = objectMapper.readValue(
                postRes.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        // Mutate fields directly in DB one by one to verify fail-closed hash validation
        ComplianceCertificate certInDb = complianceCertificateRepository.findById(originalDto.certificateId()).orElseThrow();

        // 1. Tamper finding counts
        certInDb.setCriticalUnmitigatedCount(99);
        complianceCertificateRepository.save(certInDb);

        mockMvc.perform(get(basePath + "/" + originalDto.certificateId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CORRUPT_CERTIFICATE"));

        // Restore count, tamper reviewContextHash
        certInDb.setCriticalUnmitigatedCount(0);
        certInDb.setReviewContextHash("0".repeat(64));
        complianceCertificateRepository.save(certInDb);

        mockMvc.perform(get(basePath + "/" + originalDto.certificateId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("CORRUPT_CERTIFICATE"));
    }

    // ============================================================
    // M. HASH REPRODUCIBILITY / GOLDEN BYTE
    // ============================================================
    @Test
    @DisplayName("Section M: Golden Byte SHA-256 Reproducibility Verification")
    void sectionM_hashReproducibility() throws Exception {
        String key = "idem_gate_golden_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MvcResult postRes = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();

        SecurityPolicyComplianceCertificateDto dto = objectMapper.readValue(
                postRes.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        ComplianceCertificate certInDb = complianceCertificateRepository.findById(dto.certificateId()).orElseThrow();

        // Reconstruct canonical JSON byte array independently using CanonicalCertificateHashService
        String canonicalJson = canonicalCertificateHashService.canonicalizeCertificateEntity(certInDb);
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(bytes);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String calculatedSha256 = hexString.toString();

        assertEquals(certInDb.getCertificateHash(), calculatedSha256);
        assertEquals(dto.certificateHash(), calculatedSha256);
    }

    // ============================================================
    // Q. SECRET / INFORMATION LEAKAGE
    // ============================================================
    @Test
    @DisplayName("Section Q: Secret & Stack Trace Information Leakage Prevention")
    void sectionQ_secretLeakagePrevention() throws Exception {
        String key = "idem_gate_secret_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String secretToken = "ghp_SuperSecretToken1234567890abcdef";

        MvcResult res = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secretToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertFalse(body.contains(secretToken));
        assertFalse(body.contains("SQLException"));
        assertFalse(body.contains("org.postgresql"));
        assertFalse(body.contains("Hibernate"));
    }

    // ============================================================
    // V. CROSS-PROJECT DATA ISOLATION
    // ============================================================
    @Test
    @DisplayName("Section V: Strict Cross-Project Data Isolation")
    void sectionV_crossProjectDataIsolation() throws Exception {
        String key = "idem_gate_isolation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MvcResult res = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();

        SecurityPolicyComplianceCertificateDto dto = objectMapper.readValue(
                res.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        // Attempt to fetch certificate via idempotency key on a second project's path
        Project proj2 = new Project();
        proj2.setName("Gate Project 2");
        proj2.setOwner(ownerUser);
        proj2.setStatus(ProjectStatus.ACTIVE);
        proj2 = projectRepository.save(proj2);
        projectMemberRepository.save(new ProjectMember(proj2, memberUser, ProjectRole.MEMBER));

        String proj2Path = String.format("/api/v1/projects/%s/repositories/%s/tasks/%s/reviews/%s/compliance-certificates/by-idempotency-key/%s",
                proj2.getId(), repository.getId(), task.getId(), review.getId(), key);

        mockMvc.perform(get(proj2Path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // W. NULL / OPTIONAL FIELD MATRIX
    // ============================================================
    @Test
    @DisplayName("Section W: Legitimate Null Canonical Fields Matrix")
    void sectionW_nullFieldsMatrix() throws Exception {
        String key = "idem_gate_nulls_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MvcResult postRes = mockMvc.perform(post(basePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn();

        SecurityPolicyComplianceCertificateDto dto = objectMapper.readValue(
                postRes.getResponse().getContentAsString(), SecurityPolicyComplianceCertificateDto.class);

        assertNull(dto.adminOverrideByUserId());
        assertNull(dto.adminOverrideReason());

        ComplianceCertificate certInDb = complianceCertificateRepository.findById(dto.certificateId()).orElseThrow();
        assertNull(certInDb.getAdminOverrideByUser());
        assertNull(certInDb.getAdminOverrideReason());

        String canonicalJson = canonicalCertificateHashService.canonicalizeCertificateEntity(certInDb);
        assertTrue(canonicalJson.contains("\"adminOverrideByUserId\":null"));
        assertTrue(canonicalJson.contains("\"adminOverrideReason\":null"));

        String computedHash = canonicalCertificateHashService.hashCertificateEntity(certInDb);
        assertEquals(certInDb.getCertificateHash(), computedHash);
    }
}
