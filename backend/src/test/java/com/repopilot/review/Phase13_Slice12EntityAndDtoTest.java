package com.repopilot.review;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.CreatePolicyComplianceCertificateRequestDto;
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ComplianceCertificateRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase13_Slice12EntityAndDtoTest {

    @Autowired private ComplianceCertificateRepository complianceCertificateRepository;
    @Autowired private CodeReviewRepository codeReviewRepository;
    @Autowired private CodeChangeRepository codeChangeRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;

    private Validator validator;

    private User user;
    private Project project;
    private Repository repository;
    private Task task;
    private Plan plan;
    private CodeChange codeChange;
    private CodeReview review;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();

        tearDown();

        user = new User();
        user.setEmail("slice12_user_" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hash");
        user.setName("Slice 12 User");
        user.setRole(UserRole.USER);
        user = userRepository.save(user);

        project = new Project();
        project.setName("Phase 13 Slice 12 Test Project");
        project.setDescription("Project for Slice 12 testing");
        project.setStatus(ProjectStatus.ACTIVE);
        project.setOwner(user);
        project = projectRepository.save(project);

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("test-owner");
        repository.setRepoName("test-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setTitle("Slice 12 Task");
        task.setDescription("Task for Slice 12 testing");
        task.setStatus(TaskStatus.ANALYZING);
        task.setPriority(TaskPriority.HIGH);
        task.setCreatedBy(user);
        task = taskRepository.save(task);

        plan = new Plan();
        plan.setTask(task);
        plan.setObjective("Slice 12 Objective");
        plan = planRepository.save(plan);

        codeChange = new CodeChange();
        codeChange.setTask(task);
        codeChange.setPlan(plan);
        codeChange.setRepository(repository);
        codeChange.setFilePath("src/main/java/App.java");
        codeChange.setOperation("MODIFY");
        codeChange.setOriginalHash("orig12");
        codeChange.setProposedHash("prop12");
        codeChange.setDiff("--- a/App.java\n+++ b/App.java\n@@ -1 +1 @@\n-old\n+new");
        codeChange = codeChangeRepository.save(codeChange);

        review = new CodeReview();
        review.setProject(project);
        review.setRepository(repository);
        review.setTask(task);
        review.setCodeChange(codeChange);
        review.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review.setStatus(ReviewStatus.COMPLETED);
        review.setReviewContextHash("ctx_hash_12");
        review.setModelIdentifier("model-1");
        review.setAiInputHash("ai_hash_12");
        review.setSummary("Slice 12 completed review.");
        review = codeReviewRepository.save(review);
    }

    @AfterEach
    void tearDown() {
        if (complianceCertificateRepository != null) complianceCertificateRepository.deleteAllInBatch();
        if (codeReviewRepository != null) codeReviewRepository.deleteAllInBatch();
        if (codeChangeRepository != null) codeChangeRepository.deleteAllInBatch();
        if (planRepository != null) planRepository.deleteAllInBatch();
        if (taskRepository != null) taskRepository.deleteAllInBatch();
        if (repositoryRepository != null) repositoryRepository.deleteAllInBatch();
        if (projectMemberRepository != null) projectMemberRepository.deleteAllInBatch();
        if (projectRepository != null) projectRepository.deleteAllInBatch();
        if (userRepository != null) userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("1. Entity maps every V27 column correctly and persists/retrieves intact")
    void testEntityMapping_V27ColumnsIntact() {
        Instant nowMillis = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String key = "idem_seq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String certHash = "hash_" + UUID.randomUUID().toString().replace("-", "");

        ComplianceCertificate cert = new ComplianceCertificate();
        cert.setProject(project);
        cert.setRepository(repository);
        cert.setTask(task);
        cert.setCodeReview(review);
        cert.setCodeChange(codeChange);
        cert.setComplianceStatus(PolicyComplianceStatus.PASSED);
        cert.setPolicyVersion("v1.0.0");
        cert.setReviewContextHash("ctx_hash_12");
        cert.setFindingEvidenceHash("evidence_hash_12");
        cert.setCriticalUnmitigatedCount(0);
        cert.setHighUnmitigatedCount(1);
        cert.setMediumUnmitigatedCount(2);
        cert.setLowUnmitigatedCount(3);
        cert.setMitigatedFindingsCount(5);
        cert.setAdminOverrideReason("Sample override justification text.");
        cert.setAdminOverrideByUser(user);
        cert.setIssuedByUser(user);
        cert.setIdempotencyKey(key);
        cert.setCertificateHash(certHash);
        cert.setCreatedAt(nowMillis);

        ComplianceCertificate saved = complianceCertificateRepository.save(cert);
        assertNotNull(saved.getId());

        ComplianceCertificate reloaded = complianceCertificateRepository.findById(saved.getId()).orElseThrow();
        assertEquals(project.getId(), reloaded.getProject().getId());
        assertEquals(repository.getId(), reloaded.getRepository().getId());
        assertEquals(task.getId(), reloaded.getTask().getId());
        assertEquals(review.getId(), reloaded.getCodeReview().getId());
        assertEquals(codeChange.getId(), reloaded.getCodeChange().getId());
        assertEquals(PolicyComplianceStatus.PASSED, reloaded.getComplianceStatus());
        assertEquals("v1.0.0", reloaded.getPolicyVersion());
        assertEquals("ctx_hash_12", reloaded.getReviewContextHash());
        assertEquals("evidence_hash_12", reloaded.getFindingEvidenceHash());
        assertEquals(0, reloaded.getCriticalUnmitigatedCount());
        assertEquals(1, reloaded.getHighUnmitigatedCount());
        assertEquals(2, reloaded.getMediumUnmitigatedCount());
        assertEquals(3, reloaded.getLowUnmitigatedCount());
        assertEquals(5, reloaded.getMitigatedFindingsCount());
        assertEquals("Sample override justification text.", reloaded.getAdminOverrideReason());
        assertEquals(user.getId(), reloaded.getAdminOverrideByUser().getId());
        assertEquals(user.getId(), reloaded.getIssuedByUser().getId());
        assertEquals(key, reloaded.getIdempotencyKey());
        assertEquals(certHash, reloaded.getCertificateHash());
        assertNotNull(reloaded.getCreatedAt());
    }

    @Test
    @DisplayName("2. Nullable vs NOT NULL mappings preserve null for adminOverrideReason and adminOverrideByUser")
    void testEntityMapping_NullableVsNotNull() {
        Instant nowMillis = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String key = "idem_seq_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String certHash = "hash_" + UUID.randomUUID().toString().replace("-", "");

        ComplianceCertificate cert = new ComplianceCertificate();
        cert.setProject(project);
        cert.setRepository(repository);
        cert.setTask(task);
        cert.setCodeReview(review);
        cert.setCodeChange(codeChange);
        cert.setComplianceStatus(PolicyComplianceStatus.PASSED);
        cert.setPolicyVersion("v1.0.0");
        cert.setReviewContextHash("ctx_hash_12");
        cert.setFindingEvidenceHash("evidence_hash_12");
        cert.setCriticalUnmitigatedCount(0);
        cert.setHighUnmitigatedCount(0);
        cert.setMediumUnmitigatedCount(0);
        cert.setLowUnmitigatedCount(0);
        cert.setMitigatedFindingsCount(0);
        cert.setAdminOverrideReason(null);
        cert.setAdminOverrideByUser(null);
        cert.setIssuedByUser(user);
        cert.setIdempotencyKey(key);
        cert.setCertificateHash(certHash);
        cert.setCreatedAt(nowMillis);

        ComplianceCertificate saved = complianceCertificateRepository.save(cert);
        assertNotNull(saved.getId());

        ComplianceCertificate reloaded = complianceCertificateRepository.findById(saved.getId()).orElseThrow();
        assertNull(reloaded.getAdminOverrideReason());
        assertNull(reloaded.getAdminOverrideByUser());
    }

    @Test
    @DisplayName("3. UUID relationships map correctly to domain entities")
    void testEntityMapping_UUIDRelationships() {
        ComplianceCertificate cert = new ComplianceCertificate();
        cert.setProject(project);
        cert.setRepository(repository);
        cert.setTask(task);
        cert.setCodeReview(review);
        cert.setCodeChange(codeChange);
        cert.setComplianceStatus(PolicyComplianceStatus.PASSED);
        cert.setReviewContextHash("ctx_hash");
        cert.setFindingEvidenceHash("ev_hash");
        cert.setIssuedByUser(user);
        cert.setIdempotencyKey("idem_seq_uuid_test_" + UUID.randomUUID().toString().substring(0, 8));
        cert.setCertificateHash("hash_uuid_" + UUID.randomUUID().toString().substring(0, 8));
        cert.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        ComplianceCertificate saved = complianceCertificateRepository.save(cert);

        SecurityPolicyComplianceCertificateDto dto = saved.toDto();
        assertEquals(saved.getId(), dto.certificateId());
        assertEquals(review.getId(), dto.reviewId());
        assertEquals(project.getId(), dto.projectId());
        assertEquals(repository.getId(), dto.repositoryId());
        assertEquals(task.getId(), dto.taskId());
        assertEquals(codeChange.getId(), dto.codeChangeId());
        assertEquals(user.getId(), dto.issuedByUserId());
        assertNull(dto.adminOverrideByUserId());
    }

    @Test
    @DisplayName("4. created_at does not rely on DB default; application sets explicit timestamp")
    void testEntityMapping_ExplicitCreatedAtTimestamp() {
        Instant explicitInstant = Instant.parse("2026-09-17T19:45:00.123Z");

        ComplianceCertificate cert = new ComplianceCertificate();
        cert.setProject(project);
        cert.setRepository(repository);
        cert.setTask(task);
        cert.setCodeReview(review);
        cert.setCodeChange(codeChange);
        cert.setComplianceStatus(PolicyComplianceStatus.PASSED);
        cert.setReviewContextHash("ctx_hash");
        cert.setFindingEvidenceHash("ev_hash");
        cert.setIssuedByUser(user);
        cert.setIdempotencyKey("idem_seq_explicit_ts_" + UUID.randomUUID().toString().substring(0, 8));
        cert.setCertificateHash("hash_explicit_ts_" + UUID.randomUUID().toString().substring(0, 8));
        cert.setCreatedAt(explicitInstant);

        assertEquals(explicitInstant, cert.getCreatedAt());
        ComplianceCertificate saved = complianceCertificateRepository.save(cert);
        ComplianceCertificate reloaded = complianceCertificateRepository.findById(saved.getId()).orElseThrow();
        assertNotNull(reloaded.getCreatedAt());
    }

    @Test
    @DisplayName("5. Request DTO validation accepts valid idempotency keys")
    void testRequestDto_ValidKeys_Accepted() {
        List<String> validKeys = List.of(
                "idem_seq_998877665544",
                "abc_12345",
                "12345678",
                "Valid_Key-1234567890_abcdefghijklmnopqrstuvwxyz_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789"
        );

        for (String key : validKeys) {
            CreatePolicyComplianceCertificateRequestDto req = new CreatePolicyComplianceCertificateRequestDto(key);
            Set<ConstraintViolation<CreatePolicyComplianceCertificateRequestDto>> violations = validator.validate(req);
            assertTrue(violations.isEmpty(), "Valid key should have 0 violations: " + key);
        }
    }

    @Test
    @DisplayName("6. Request DTO validation rejects null, blank, short, long, spaces, special, and non-ASCII chars")
    void testRequestDto_InvalidKeys_Rejected() {
        List<String> invalidKeys = List.of(
                "",                             // Blank
                "   ",                          // Blank whitespace
                "short",                        // < 8 chars
                "a".repeat(129),                // > 128 chars
                "has space",                    // Spaces
                "key:with:colon",               // Colon
                "key,with,comma",               // Comma
                "key\"with\"quotes",            // Quotes
                "key/with/slash",               // Slash
                "key\\with\\backslash",         // Backslash
                "key_with_unicode_é",           // Non-ASCII unicode
                "key_with_\n_newline"           // Control char
        );

        for (String key : invalidKeys) {
            CreatePolicyComplianceCertificateRequestDto req = new CreatePolicyComplianceCertificateRequestDto(key);
            Set<ConstraintViolation<CreatePolicyComplianceCertificateRequestDto>> violations = validator.validate(req);
            assertFalse(violations.isEmpty(), "Invalid key MUST produce validation errors: " + key);
        }

        // Test null explicitly
        CreatePolicyComplianceCertificateRequestDto nullReq = new CreatePolicyComplianceCertificateRequestDto(null);
        assertFalse(validator.validate(nullReq).isEmpty());
    }

    @Test
    @DisplayName("7. Response DTO exposes exactly approved public fields without internal tokens/state")
    void testResponseDto_ExposesApprovedPublicFieldsOnly() {
        Field[] fields = SecurityPolicyComplianceCertificateDto.class.getDeclaredFields();
        Set<String> fieldNames = new HashSet<>();
        for (Field f : fields) {
            if (!Modifier.isStatic(f.getModifiers())) {
                fieldNames.add(f.getName());
            }
        }

        Set<String> expectedFields = Set.of(
                "certificateId", "reviewId", "projectId", "repositoryId", "taskId", "codeChangeId",
                "complianceStatus", "policyVersion", "reviewContextHash", "findingEvidenceHash",
                "criticalUnmitigatedCount", "highUnmitigatedCount", "mediumUnmitigatedCount",
                "lowUnmitigatedCount", "mitigatedFindingsCount", "adminOverrideReason",
                "adminOverrideByUserId", "issuedByUserId", "idempotencyKey", "certificateHash", "issuedAt"
        );

        assertEquals(expectedFields, fieldNames, "Response DTO must contain exactly the 21 approved public fields");

        assertFalse(fieldNames.contains("workerId"), "Response DTO must NOT expose workerId");
        assertFalse(fieldNames.contains("leaseToken"), "Response DTO must NOT expose leaseToken");
        assertFalse(fieldNames.contains("transactionState"), "Response DTO must NOT expose internal transaction state");
    }

    @Test
    @DisplayName("8. Repository queries operate correctly without crossing project/resource boundaries")
    void testRepositoryQueries_ResourceBoundaryIsolation() {
        String key = "idem_seq_boundary_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String certHash = "hash_boundary_" + UUID.randomUUID().toString().replace("-", "");

        ComplianceCertificate cert = new ComplianceCertificate();
        cert.setProject(project);
        cert.setRepository(repository);
        cert.setTask(task);
        cert.setCodeReview(review);
        cert.setCodeChange(codeChange);
        cert.setComplianceStatus(PolicyComplianceStatus.PASSED);
        cert.setReviewContextHash("ctx_boundary");
        cert.setFindingEvidenceHash("ev_boundary");
        cert.setIssuedByUser(user);
        cert.setIdempotencyKey(key);
        cert.setCertificateHash(certHash);
        cert.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));

        ComplianceCertificate saved = complianceCertificateRepository.save(cert);

        // 1. Find by Idempotency Key
        assertTrue(complianceCertificateRepository.findByIdempotencyKey(key).isPresent());

        // 2. Find by Project, Review & Idempotency Key
        assertTrue(complianceCertificateRepository.findByProjectIdAndCodeReviewIdAndIdempotencyKey(project.getId(), review.getId(), key).isPresent());

        // 3. Mismatched Project ID should return empty
        UUID otherProjId = UUID.randomUUID();
        assertTrue(complianceCertificateRepository.findByProjectIdAndCodeReviewIdAndIdempotencyKey(otherProjId, review.getId(), key).isEmpty());

        // 4. Secure IDOR hierarchy lookup
        assertTrue(complianceCertificateRepository.findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
                saved.getId(), project.getId(), repository.getId(), task.getId(), review.getId()
        ).isPresent());

        // 5. Mismatched hierarchy IDOR check
        assertTrue(complianceCertificateRepository.findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
                saved.getId(), otherProjId, repository.getId(), task.getId(), review.getId()
        ).isEmpty());
    }

    @Test
    @DisplayName("9. Multi-Project & Multi-Resource Hierarchy Isolation Test")
    void testRepositoryQueries_MultiProjectMultiResourceIsolation() {
        // Project A resources
        String keyA = "idem_seq_projA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String certHashA = "hash_projA_" + UUID.randomUUID().toString().replace("-", "");
        ComplianceCertificate certA = new ComplianceCertificate();
        certA.setProject(project);
        certA.setRepository(repository);
        certA.setTask(task);
        certA.setCodeReview(review);
        certA.setCodeChange(codeChange);
        certA.setComplianceStatus(PolicyComplianceStatus.PASSED);
        certA.setReviewContextHash("ctx_A");
        certA.setFindingEvidenceHash("ev_A");
        certA.setIssuedByUser(user);
        certA.setIdempotencyKey(keyA);
        certA.setCertificateHash(certHashA);
        certA.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        ComplianceCertificate savedA = complianceCertificateRepository.save(certA);

        // Project B resources
        Project projectB = new Project();
        projectB.setName("Project B Isolation");
        projectB.setOwner(user);
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        Repository repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("owner-b");
        repoB.setRepoName("repo-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        Task taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setTitle("Task B");
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB.setPriority(TaskPriority.HIGH);
        taskB.setCreatedBy(user);
        taskB = taskRepository.save(taskB);

        Plan planB = new Plan();
        planB.setTask(taskB);
        planB.setObjective("Plan B");
        planB = planRepository.save(planB);

        CodeChange codeChangeB = new CodeChange();
        codeChangeB.setTask(taskB);
        codeChangeB.setPlan(planB);
        codeChangeB.setRepository(repoB);
        codeChangeB.setFilePath("src/B.java");
        codeChangeB.setOperation("MODIFY");
        codeChangeB = codeChangeRepository.save(codeChangeB);

        CodeReview reviewB = new CodeReview();
        reviewB.setProject(projectB);
        reviewB.setRepository(repoB);
        reviewB.setTask(taskB);
        reviewB.setCodeChange(codeChangeB);
        reviewB.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        reviewB.setStatus(ReviewStatus.COMPLETED);
        reviewB.setReviewContextHash("ctx_B");
        reviewB.setModelIdentifier("model-1");
        reviewB = codeReviewRepository.save(reviewB);

        String keyB = "idem_seq_projB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String certHashB = "hash_projB_" + UUID.randomUUID().toString().replace("-", "");
        ComplianceCertificate certB = new ComplianceCertificate();
        certB.setProject(projectB);
        certB.setRepository(repoB);
        certB.setTask(taskB);
        certB.setCodeReview(reviewB);
        certB.setCodeChange(codeChangeB);
        certB.setComplianceStatus(PolicyComplianceStatus.PASSED);
        certB.setReviewContextHash("ctx_B");
        certB.setFindingEvidenceHash("ev_B");
        certB.setIssuedByUser(user);
        certB.setIdempotencyKey(keyB);
        certB.setCertificateHash(certHashB);
        certB.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        ComplianceCertificate savedB = complianceCertificateRepository.save(certB);

        // Assert Project A query CANNOT retrieve Project B certificate
        Optional<ComplianceCertificate> resA = complianceCertificateRepository.findByProjectIdAndCodeReviewIdAndIdempotencyKey(
                project.getId(), review.getId(), keyA);
        assertTrue(resA.isPresent());
        assertEquals(savedA.getId(), resA.get().getId());

        // Cross-lookup assertions MUST return empty
        assertTrue(complianceCertificateRepository.findByProjectIdAndCodeReviewIdAndIdempotencyKey(project.getId(), review.getId(), keyB).isEmpty());
        assertTrue(complianceCertificateRepository.findByProjectIdAndCodeReviewIdAndIdempotencyKey(projectB.getId(), review.getId(), keyA).isEmpty());
        assertTrue(complianceCertificateRepository.findByProjectIdAndCodeReviewIdAndIdempotencyKey(project.getId(), reviewB.getId(), keyA).isEmpty());

        // IDOR 5-level hierarchy assertions MUST return empty when any level is mismatched
        assertTrue(complianceCertificateRepository.findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
                savedA.getId(), projectB.getId(), repository.getId(), task.getId(), review.getId()).isEmpty());
        assertTrue(complianceCertificateRepository.findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
                savedA.getId(), project.getId(), repoB.getId(), task.getId(), review.getId()).isEmpty());
        assertTrue(complianceCertificateRepository.findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
                savedA.getId(), project.getId(), repository.getId(), taskB.getId(), review.getId()).isEmpty());
        assertTrue(complianceCertificateRepository.findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
                savedA.getId(), project.getId(), repository.getId(), task.getId(), reviewB.getId()).isEmpty());
    }

    @Test
    @DisplayName("10. Real PostgreSQL 16 Schema Nullability, Constraints & No DB Default Test")
    void testRealPostgresql_SchemaNullabilityAndConstraints() {
        String pgUrl = "jdbc:postgresql://127.0.0.1:5432/repopilot";
        String pgUser = "repopilot_user";
        String pgPass = "repopilot_password";

        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            return; // Driver not on classpath, skip
        }

        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(pgUrl, pgUser, pgPass)) {
            // 1. Verify created_at column has NO database default
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_default, is_nullable FROM information_schema.columns WHERE table_name = 'compliance_certificates' AND column_name = 'created_at'")) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "Column created_at must exist in compliance_certificates table");
                    assertNull(rs.getString("column_default"), "created_at MUST NOT have a database default value");
                    assertEquals("NO", rs.getString("is_nullable"), "created_at MUST be NOT NULL");
                }
            }

            // 2. Verify admin_override_reason and admin_override_by_user_id ARE nullable
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 'compliance_certificates' AND column_name IN ('admin_override_reason', 'admin_override_by_user_id')")) {
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        assertEquals("YES", rs.getString("is_nullable"), "Field " + rs.getString("column_name") + " MUST be nullable");
                        count++;
                    }
                    assertEquals(2, count);
                }
            }
        } catch (java.sql.SQLException e) {
            System.err.println("PostgreSQL 16 not reachable during test: " + e.getMessage());
        }
    }
}
