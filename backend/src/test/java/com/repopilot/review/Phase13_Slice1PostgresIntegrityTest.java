package com.repopilot.review;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase13_Slice1PostgresIntegrityTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

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

    private User testUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask;
    private Plan testPlan;
    private CodeChange testCodeChange;

    private static final String PG_URL = "jdbc:postgresql://127.0.0.1:5432/repopilot";
    private static final String PG_USER = "repopilot_user";
    private static final String PG_PASS = "repopilot_password";

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setEmail("phase13_slice1_user_" + UUID.randomUUID() + "@repopilot.ai");
        testUser.setPasswordHash("hash123");
        testUser.setName("Phase13 SecOps Tester");
        testUser.setRole(UserRole.ADMIN);
        testUser = userRepository.save(testUser);

        testProject = new Project();
        testProject.setName("Phase 13 Integrity Project");
        testProject.setDescription("Testing Persistence & Immutability Boundary");
        testProject.setOwner(testUser);
        testProject.setStatus(ProjectStatus.ACTIVE);
        testProject = projectRepository.save(testProject);

        testRepo = new Repository();
        testRepo.setProject(testProject);
        testRepo.setOwnerName("repopilot");
        testRepo.setRepoName("slice1-integrity-repo-" + UUID.randomUUID().toString().substring(0, 8));
        testRepo.setDefaultBranch("main");
        testRepo.setVisibility(RepositoryVisibility.PUBLIC);
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        testRepo = repositoryRepository.save(testRepo);

        testTask = new Task();
        testTask.setProject(testProject);
        testTask.setRepository(testRepo);
        testTask.setCreatedBy(testUser);
        testTask.setTitle("Phase 13 Hardening Task");
        testTask.setDescription("Verify DB immutability and approval eligibility");
        testTask.setStatus(TaskStatus.ANALYZING);
        testTask.setPriority(TaskPriority.HIGH);
        testTask = taskRepository.save(testTask);

        testPlan = new Plan();
        testPlan.setTask(testTask);
        testPlan.setObjective("Enforce Flyway V24 Schema");
        testPlan = planRepository.save(testPlan);

        testCodeChange = new CodeChange();
        testCodeChange.setTask(testTask);
        testCodeChange.setPlan(testPlan);
        testCodeChange.setFilePath("src/main/java/com/repopilot/review/PolicyEngine.java");
        testCodeChange.setOperation("MODIFY");
        testCodeChange = codeChangeRepository.save(testCodeChange);
    }

    @Test
    @DisplayName("Slice 1: Verify CodeReview V24 JPA Entity Mapping and Default Field Values")
    void testV24EntityMappingAndDefaults() {
        CodeReview review = new CodeReview();
        review.setProject(testProject);
        review.setRepository(testRepo);
        review.setTask(testTask);
        review.setCodeChange(testCodeChange);
        review.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review.setStatus(ReviewStatus.COMPLETED);
        review.setReviewContextHash("b1388fc009e4d02636a0487955c4d375edef0c78a08d277d33d9c8eb010e9760");
        review.setModelIdentifier("gemini-2.5-pro");
        review.setCompletedAt(Instant.now());

        CodeReview saved = codeReviewRepository.save(review);
        assertNotNull(saved.getId());
        assertNull(saved.getAiInputHash(), "Historical/legacy rows must have null ai_input_hash");
        assertEquals("v1.0.0", saved.getPolicyVersion());
        assertEquals("1.0.0", saved.getReviewerVersion());
        assertEquals("p2026.1", saved.getPromptVersion());
        assertEquals("1.0.0", saved.getValidatorVersion());
        assertNull(saved.getAdminOverrideReason());
        assertNull(saved.getAdminOverrideByUser());
        assertNull(saved.getAdminOverrideAt());
    }

    @Test
    @DisplayName("Slice 1: Verify Historical ai_input_hash Nullability Invariant")
    void testLegacyAiInputHashNullInvariant() {
        CodeReview legacyReview = new CodeReview();
        legacyReview.setProject(testProject);
        legacyReview.setRepository(testRepo);
        legacyReview.setTask(testTask);
        legacyReview.setCodeChange(testCodeChange);
        legacyReview.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        legacyReview.setStatus(ReviewStatus.COMPLETED);
        legacyReview.setReviewContextHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        legacyReview.setModelIdentifier("gemini-2.5-pro");

        CodeReview saved = codeReviewRepository.save(legacyReview);
        assertNull(saved.getAiInputHash(), "Historical legacy review MUST have NULL ai_input_hash");
        assertNotEquals("0000000000000000000000000000000000000000000000000000000000000000", saved.getAiInputHash(),
                "NULL ai_input_hash must never equal a genuine 64-char SHA-256 hash string");
    }

    @Test
    @DisplayName("Slice 1: Verify CodeReviewRepository.findEligibleCompletedReviews Query")
    void testApprovalEligibilityQuery_FindEligibleCompletedReviews() {
        String matchingHash = "c2499fc009e4d02636a0487955c4d375edef0c78a08d277d33d9c8eb010e9761";
        String differentHash = "d3500fc009e4d02636a0487955c4d375edef0c78a08d277d33d9c8eb010e9762";

        Instant newerTime = Instant.now();

        // Create additional CodeChange objects to avoid UNIQUE(code_change_id, review_context_hash) violation
        CodeChange change2 = createAdditionalCodeChange("src/main/java/com/repopilot/review/PolicyEngine2.java");
        CodeChange change3 = createAdditionalCodeChange("src/main/java/com/repopilot/review/PolicyEngine3.java");
        CodeChange change4 = createAdditionalCodeChange("src/main/java/com/repopilot/review/PolicyEngine4.java");
        CodeChange change5 = createAdditionalCodeChange("src/main/java/com/repopilot/review/PolicyEngine5.java");

        // 1. Target completed review with matching hash on testCodeChange
        CodeReview targetCompleted = new CodeReview();
        targetCompleted.setProject(testProject);
        targetCompleted.setRepository(testRepo);
        targetCompleted.setTask(testTask);
        targetCompleted.setCodeChange(testCodeChange);
        targetCompleted.setReviewContextHash(matchingHash);
        targetCompleted.setModelIdentifier("gemini-2.5-pro");
        targetCompleted.setStatus(ReviewStatus.COMPLETED);
        targetCompleted.setCompletedAt(newerTime);
        codeReviewRepository.save(targetCompleted);

        // 2. In-progress review on change2 (MUST be excluded)
        CodeReview runningReview = new CodeReview();
        runningReview.setProject(testProject);
        runningReview.setRepository(testRepo);
        runningReview.setTask(testTask);
        runningReview.setCodeChange(change2);
        runningReview.setReviewContextHash(matchingHash);
        runningReview.setModelIdentifier("gemini-2.5-pro");
        runningReview.setStatus(ReviewStatus.IN_PROGRESS);
        codeReviewRepository.save(runningReview);

        // 3. Failed review on change3 (MUST be excluded)
        CodeReview failedReview = new CodeReview();
        failedReview.setProject(testProject);
        failedReview.setRepository(testRepo);
        failedReview.setTask(testTask);
        failedReview.setCodeChange(change3);
        failedReview.setReviewContextHash(matchingHash);
        failedReview.setModelIdentifier("gemini-2.5-pro");
        failedReview.setStatus(ReviewStatus.FAILED);
        failedReview.setCompletedAt(Instant.now());
        codeReviewRepository.save(failedReview);

        // 4. Stale review on change4 (MUST be excluded)
        CodeReview staleReview = new CodeReview();
        staleReview.setProject(testProject);
        staleReview.setRepository(testRepo);
        staleReview.setTask(testTask);
        staleReview.setCodeChange(change4);
        staleReview.setReviewContextHash(matchingHash);
        staleReview.setModelIdentifier("gemini-2.5-pro");
        staleReview.setStatus(ReviewStatus.STALE);
        staleReview.setCompletedAt(Instant.now());
        codeReviewRepository.save(staleReview);

        // 5. Context too large review on change5 (MUST be excluded)
        CodeReview contextTooLargeReview = new CodeReview();
        contextTooLargeReview.setProject(testProject);
        contextTooLargeReview.setRepository(testRepo);
        contextTooLargeReview.setTask(testTask);
        contextTooLargeReview.setCodeChange(change5);
        contextTooLargeReview.setReviewContextHash(matchingHash);
        contextTooLargeReview.setModelIdentifier("gemini-2.5-pro");
        contextTooLargeReview.setStatus(ReviewStatus.REJECTED_CONTEXT_TOO_LARGE);
        contextTooLargeReview.setCompletedAt(Instant.now());
        codeReviewRepository.save(contextTooLargeReview);

        // 6. Completed review on testCodeChange with mismatching hash (MUST be excluded)
        CodeReview differentHashCompleted = new CodeReview();
        differentHashCompleted.setProject(testProject);
        differentHashCompleted.setRepository(testRepo);
        differentHashCompleted.setTask(testTask);
        differentHashCompleted.setCodeChange(testCodeChange);
        differentHashCompleted.setReviewContextHash(differentHash);
        differentHashCompleted.setModelIdentifier("gemini-2.5-pro");
        differentHashCompleted.setStatus(ReviewStatus.COMPLETED);
        differentHashCompleted.setCompletedAt(Instant.now());
        codeReviewRepository.save(differentHashCompleted);

        // Execute eligibility query for testCodeChange and matchingHash
        List<CodeReview> eligibleReviews = codeReviewRepository.findEligibleCompletedReviews(
                testProject.getId(),
                testRepo.getId(),
                testTask.getId(),
                testCodeChange.getId(),
                matchingHash
        );

        assertNotNull(eligibleReviews, "Eligible reviews list must not be null");
        assertEquals(1, eligibleReviews.size(), "Query must return exactly 1 matching COMPLETED review for testCodeChange");
        assertEquals(targetCompleted.getId(), eligibleReviews.get(0).getId(), "Result must be the target completed review");
    }

    @Test
    @DisplayName("Slice 1: Adversarial PostgreSQL Trigger Test - All 14 Scenarios (A through N)")
    void testAdversarialAdminOverride_Scenarios_A_to_N() throws Exception {
        if (!isPostgresAvailable()) {
            System.out.println("Skipping PostgreSQL trigger direct test - PostgreSQL container not reachable on 127.0.0.1:5432");
            return;
        }

        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            conn.setAutoCommit(false);
            UUID userId = testUser.getId();

            // --- Scenario A: reason-only update → REJECTED ---
            UUID idA = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idA, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'Reason Only' WHERE id = ?")) {
                ps.setObject(1, idA);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario A: Reason-only update must be REJECTED");
                assertTrue(ex.getMessage().contains("must populate admin_override_reason, admin_override_by_user_id, and admin_override_at atomically"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario B: userId-only update → REJECTED ---
            UUID idB = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idB, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_by_user_id = ? WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idB);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario B: UserId-only update must be REJECTED");
                assertTrue(ex.getMessage().contains("must populate admin_override_reason, admin_override_by_user_id, and admin_override_at atomically"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario C: timestamp-only update → REJECTED ---
            UUID idC = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idC, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, idC);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario C: Timestamp-only update must be REJECTED");
                assertTrue(ex.getMessage().contains("must populate admin_override_reason, admin_override_by_user_id, and admin_override_at atomically"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario D: reason + userId without timestamp → REJECTED ---
            UUID idD = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idD, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'Reason', admin_override_by_user_id = ? WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idD);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario D: Reason + UserId without timestamp must be REJECTED");
                assertTrue(ex.getMessage().contains("must populate admin_override_reason, admin_override_by_user_id, and admin_override_at atomically"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario E: reason + timestamp without userId → REJECTED ---
            UUID idE = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idE, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'Reason', admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, idE);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario E: Reason + Timestamp without userId must be REJECTED");
                assertTrue(ex.getMessage().contains("must populate admin_override_reason, admin_override_by_user_id, and admin_override_at atomically"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario F: userId + timestamp without reason → REJECTED ---
            UUID idF = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idF, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idF);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario F: UserId + Timestamp without reason must be REJECTED");
                assertTrue(ex.getMessage().contains("must populate admin_override_reason, admin_override_by_user_id, and admin_override_at atomically"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario G: all three populated in one UPDATE → ACCEPTED ---
            UUID idG = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idG, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'SecOps Approval', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idG);
                int count = ps.executeUpdate();
                assertEquals(1, count, "Scenario G: Populating all three columns in one UPDATE must be ACCEPTED");
            }

            // --- Scenario H: second modification of reason → REJECTED ---
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'Tampered Reason' WHERE id = ?")) {
                ps.setObject(1, idG);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario H: Second modification of reason must be REJECTED");
                assertTrue(ex.getMessage().contains("Admin override has already been assigned"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario I: second modification of userId → REJECTED ---
            UUID idI = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idI, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'SecOps Approval', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idI);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_by_user_id = ? WHERE id = ?")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, idI);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario I: Second modification of userId must be REJECTED");
                assertTrue(ex.getMessage().contains("Admin override has already been assigned"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario J: second modification of timestamp → REJECTED ---
            UUID idJ = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idJ, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'SecOps Approval', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idJ);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_at = NOW() + INTERVAL '1 second' WHERE id = ?")) {
                ps.setObject(1, idJ);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario J: Second modification of timestamp must be REJECTED");
                assertTrue(ex.getMessage().contains("Admin override has already been assigned"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario K: clearing any override field → REJECTED ---
            UUID idK = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idK, "COMPLETED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'SecOps Approval', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idK);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = NULL WHERE id = ?")) {
                ps.setObject(1, idK);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario K: Clearing override field must be REJECTED");
                assertTrue(ex.getMessage().contains("Admin override has already been assigned"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario L: override on FAILED → REJECTED ---
            UUID idL = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idL, "FAILED");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'Reason', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idL);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario L: Override on FAILED must be REJECTED");
                assertTrue(ex.getMessage().contains("Admin override is permitted only on COMPLETED CodeReview records"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario M: override on STALE → REJECTED ---
            UUID idM = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idM, "STALE");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'Reason', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idM);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario M: Override on STALE must be REJECTED");
                assertTrue(ex.getMessage().contains("Admin override is permitted only on COMPLETED CodeReview records"), ex.getMessage());
            }
            conn.rollback();

            // --- Scenario N: override on RUNNING (IN_PROGRESS) → REJECTED ---
            UUID idN = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, idN, "IN_PROGRESS");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET admin_override_reason = 'Reason', admin_override_by_user_id = ?, admin_override_at = NOW() WHERE id = ?")) {
                ps.setObject(1, userId);
                ps.setObject(2, idN);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate, "Scenario N: Override on RUNNING (IN_PROGRESS) must be REJECTED");
                assertTrue(ex.getMessage().contains("Admin override is permitted only on COMPLETED CodeReview records"), ex.getMessage());
            }
            conn.rollback();
        }
    }

    @Test
    @DisplayName("Slice 1: Verify PostgreSQL Trigger 'prevent_code_review_unauthorized_update' Immutability on Terminal States")
    void testPostgresCodeReviewImmutabilityTrigger() throws Exception {
        if (!isPostgresAvailable()) {
            return;
        }

        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            conn.setAutoCommit(false);

            String[] terminalStatuses = {"COMPLETED", "FAILED", "STALE", "STALE_REVIEW", "REJECTED_CONTEXT_TOO_LARGE"};

            for (String status : terminalStatuses) {
                // Attempt 1: Update protected field 'summary' on terminal record
                UUID reviewId1 = UUID.randomUUID();
                insertTestCodeReviewPostgres(conn, reviewId1, status);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET summary = 'Tampered' WHERE id = ?")) {
                    ps.setObject(1, reviewId1);
                    SQLException ex = assertThrows(SQLException.class, ps::executeUpdate,
                            "PostgreSQL must throw SQLException when updating summary on terminal CodeReview " + status);
                    assertTrue(ex.getMessage().contains("Attempted to modify protected immutable field on terminal CodeReview"),
                            "Exception message must identify immutable violation: " + ex.getMessage());
                }
                conn.rollback();

                // Attempt 2: Update protected field 'status' on terminal record
                UUID reviewId2 = UUID.randomUUID();
                insertTestCodeReviewPostgres(conn, reviewId2, status);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET status = 'IN_PROGRESS' WHERE id = ?")) {
                    ps.setObject(1, reviewId2);
                    SQLException ex = assertThrows(SQLException.class, ps::executeUpdate,
                            "PostgreSQL must throw SQLException when updating status on terminal CodeReview " + status);
                    assertTrue(ex.getMessage().contains("Attempted to modify protected immutable field on terminal CodeReview"),
                            "Exception message must identify immutable violation: " + ex.getMessage());
                }
                conn.rollback();

                // Attempt 3: Update protected field 'review_context_hash' on terminal record
                UUID reviewId3 = UUID.randomUUID();
                insertTestCodeReviewPostgres(conn, reviewId3, status);
                try (PreparedStatement ps = conn.prepareStatement("UPDATE code_reviews SET review_context_hash = 'tampered_hash' WHERE id = ?")) {
                    ps.setObject(1, reviewId3);
                    SQLException ex = assertThrows(SQLException.class, ps::executeUpdate,
                            "PostgreSQL must throw SQLException when updating review_context_hash on terminal CodeReview " + status);
                    assertTrue(ex.getMessage().contains("Attempted to modify protected immutable field on terminal CodeReview"),
                            "Exception message must identify immutable violation: " + ex.getMessage());
                }
                conn.rollback();
            }
        }
    }

    @Test
    @DisplayName("Slice 1: Verify PostgreSQL Trigger 'prevent_review_finding_protected_update' Immutability")
    void testPostgresReviewFindingImmutabilityTrigger() throws Exception {
        if (!isPostgresAvailable()) {
            return;
        }

        try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
            conn.setAutoCommit(false);

            // 1. Attempt to update protected field 'title' MUST fail
            UUID reviewId1 = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, reviewId1, "COMPLETED");
            UUID findingId1 = UUID.randomUUID();
            insertTestReviewFindingPostgres(conn, findingId1, reviewId1);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE review_findings SET title = 'Altered Title' WHERE id = ?")) {
                ps.setObject(1, findingId1);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate,
                        "PostgreSQL must reject updating protected title on review_finding");
                assertTrue(ex.getMessage().contains("Attempted to modify protected immutable field on ReviewFinding"),
                        "Exception must mention immutable field violation: " + ex.getMessage());
            }
            conn.rollback();

            // 2. Attempt to update protected field 'severity' MUST fail
            UUID reviewId2 = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, reviewId2, "COMPLETED");
            UUID findingId2 = UUID.randomUUID();
            insertTestReviewFindingPostgres(conn, findingId2, reviewId2);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE review_findings SET severity = 'LOW' WHERE id = ?")) {
                ps.setObject(1, findingId2);
                SQLException ex = assertThrows(SQLException.class, ps::executeUpdate,
                        "PostgreSQL must reject updating protected severity on review_finding");
                assertTrue(ex.getMessage().contains("Attempted to modify protected immutable field on ReviewFinding"),
                        "Exception must mention immutable field violation: " + ex.getMessage());
            }
            conn.rollback();

            // 3. Updating allowed field 'status' MUST succeed
            UUID reviewId3 = UUID.randomUUID();
            insertTestCodeReviewPostgres(conn, reviewId3, "COMPLETED");
            UUID findingId3 = UUID.randomUUID();
            insertTestReviewFindingPostgres(conn, findingId3, reviewId3);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE review_findings SET status = 'RESOLVED', updated_at = NOW() WHERE id = ?")) {
                ps.setObject(1, findingId3);
                int count = ps.executeUpdate();
                assertEquals(1, count, "Updating status and updated_at on review_findings must succeed");
            }
            conn.rollback();
        }
    }

    private boolean isPostgresAvailable() {
        try {
            Class.forName("org.postgresql.Driver");
            try (Connection conn = DriverManager.getConnection(PG_URL, PG_USER, PG_PASS)) {
                return conn.isValid(2);
            }
        } catch (Exception e) {
            System.err.println("PostgreSQL connection check failed: " + e.getClass().getName() + ": " + e.getMessage());
            return false;
        }
    }

    private CodeChange createAdditionalCodeChange(String filePath) {
        CodeChange change = new CodeChange();
        change.setTask(testTask);
        change.setPlan(testPlan);
        change.setFilePath(filePath);
        change.setOperation("MODIFY");
        return codeChangeRepository.save(change);
    }

    private void ensurePostgresFixturesExist(Connection conn) throws SQLException {
        try (PreparedStatement psUser = conn.prepareStatement(
                "INSERT INTO users (id, email, password_hash, name, role) VALUES (?, ?, 'hash123', 'SecOps', 'ADMIN') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psProject = conn.prepareStatement(
                "INSERT INTO projects (id, name, owner_id, status) VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psRepo = conn.prepareStatement(
                "INSERT INTO repositories (id, project_id, owner_name, repo_name, default_branch, visibility) VALUES (?, ?, 'repopilot', ?, 'main', 'PUBLIC') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psTask = conn.prepareStatement(
                "INSERT INTO tasks (id, project_id, repository_id, created_by, title, description, status, priority) VALUES (?, ?, ?, ?, 'Task', 'Desc', 'ANALYZING', 'HIGH') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psPlan = conn.prepareStatement(
                "INSERT INTO plans (id, task_id, objective) VALUES (?, ?, 'Plan') ON CONFLICT (id) DO NOTHING");
             PreparedStatement psChange = conn.prepareStatement(
                "INSERT INTO code_changes (id, task_id, plan_id, file_path, operation) VALUES (?, ?, ?, 'src/Main.java', 'MODIFY') ON CONFLICT (id) DO NOTHING")
        ) {
            psUser.setObject(1, testUser.getId());
            psUser.setString(2, testUser.getEmail());
            psUser.executeUpdate();

            psProject.setObject(1, testProject.getId());
            psProject.setString(2, testProject.getName());
            psProject.setObject(3, testUser.getId());
            psProject.executeUpdate();

            psRepo.setObject(1, testRepo.getId());
            psRepo.setObject(2, testProject.getId());
            psRepo.setString(3, testRepo.getRepoName());
            psRepo.executeUpdate();

            psTask.setObject(1, testTask.getId());
            psTask.setObject(2, testProject.getId());
            psTask.setObject(3, testRepo.getId());
            psTask.setObject(4, testUser.getId());
            psTask.executeUpdate();

            psPlan.setObject(1, testPlan.getId());
            psPlan.setObject(2, testTask.getId());
            psPlan.executeUpdate();

            psChange.setObject(1, testCodeChange.getId());
            psChange.setObject(2, testTask.getId());
            psChange.setObject(3, testPlan.getId());
            psChange.executeUpdate();
        }
    }

    private void insertTestCodeReviewPostgres(Connection conn, UUID reviewId, String status) throws SQLException {
        ensurePostgresFixturesExist(conn);
        String sql = "INSERT INTO code_reviews (id, task_id, project_id, repository_id, code_change_id, status, review_mode, summary, overall_risk_score, " +
                "review_context_hash, ai_input_hash, policy_version, reviewer_version, prompt_version, validator_version, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'CODE_CHANGE_REVIEW', 'Initial Summary', 'LOW', ?, NULL, " +
                "'v1.0.0', '1.0.0', 'p2026.1', '1.0.0', NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, reviewId);
            ps.setObject(2, testTask.getId());
            ps.setObject(3, testProject.getId());
            ps.setObject(4, testRepo.getId());
            ps.setObject(5, testCodeChange.getId());
            ps.setString(6, status);
            ps.setString(7, "hash_" + UUID.randomUUID());
            ps.executeUpdate();
        }
    }

    private void insertTestReviewFindingPostgres(Connection conn, UUID findingId, UUID reviewId) throws SQLException {
        ensurePostgresFixturesExist(conn);
        String sql = "INSERT INTO review_findings (id, review_id, project_id, repository_id, task_id, code_change_id, " +
                "finding_type, category, cwe_id, severity, confidence, title, description, file_path, start_line, end_line, " +
                "side, content_hash, fingerprint, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'AI_FINDING', 'SQL_INJECTION', 'CWE-89', 'HIGH', 'HIGH', 'Original Title', " +
                "'Original Description', 'src/Main.java', 10, 20, 'PROPOSED', 'chash_123', 'fp_123', 'OPEN', NOW(), NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, findingId);
            ps.setObject(2, reviewId);
            ps.setObject(3, testProject.getId());
            ps.setObject(4, testRepo.getId());
            ps.setObject(5, testTask.getId());
            ps.setObject(6, testCodeChange.getId());
            ps.executeUpdate();
        }
    }
}
