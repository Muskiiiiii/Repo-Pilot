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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase11_0CodeReviewEntityRepositoryTest {

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

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setEmail("reviewer@repopilot.ai");
        testUser.setPasswordHash("hash123");
        testUser.setName("Code Reviewer");
        testUser.setRole(UserRole.USER);
        testUser = userRepository.save(testUser);

        testProject = new Project();
        testProject.setName("Phase 11 Review Project");
        testProject.setDescription("Testing AI Code Review Entities");
        testProject.setOwner(testUser);
        testProject.setStatus(ProjectStatus.ACTIVE);
        testProject = projectRepository.save(testProject);

        testRepo = new Repository();
        testRepo.setProject(testProject);
        testRepo.setOwnerName("repopilot");
        testRepo.setRepoName("review-target");
        testRepo.setDefaultBranch("main");
        testRepo.setVisibility(RepositoryVisibility.PUBLIC);
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        testRepo = repositoryRepository.save(testRepo);

        testTask = new Task();
        testTask.setProject(testProject);
        testTask.setRepository(testRepo);
        testTask.setCreatedBy(testUser);
        testTask.setTitle("Implement Security Hardening");
        testTask.setDescription("Fix SQLi and XSS vulnerabilities");
        testTask.setStatus(TaskStatus.ANALYZING);
        testTask.setPriority(TaskPriority.HIGH);
        testTask = taskRepository.save(testTask);

        testPlan = new Plan();
        testPlan.setTask(testTask);
        testPlan.setObjective("Sanitize inputs and parameterize queries");
        testPlan = planRepository.save(testPlan);

        testCodeChange = new CodeChange();
        testCodeChange.setTask(testTask);
        testCodeChange.setPlan(testPlan);
        testCodeChange.setFilePath("src/main/java/com/example/UserService.java");
        testCodeChange.setOperation("MODIFY");
        testCodeChange = codeChangeRepository.save(testCodeChange);
    }

    @Test
    @DisplayName("Verify CodeReview Entity Persistence and Defaults")
    void testCodeReviewPersistenceAndDefaults() {
        CodeReview review = new CodeReview();
        review.setProject(testProject);
        review.setRepository(testRepo);
        review.setTask(testTask);
        review.setCodeChange(testCodeChange);
        review.setReviewMode(ReviewMode.CODE_CHANGE_REVIEW);
        review.setStatus(ReviewStatus.IN_PROGRESS);
        review.setReviewContextHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        review.setModelIdentifier("gemini-2.5-pro");

        CodeReview saved = codeReviewRepository.save(review);

        assertNotNull(saved.getId());
        assertEquals(ReviewMode.CODE_CHANGE_REVIEW, saved.getReviewMode());
        assertEquals(ReviewStatus.IN_PROGRESS, saved.getStatus());
        assertEquals("LOW", saved.getOverallRiskScore());
        assertNotNull(saved.getCreatedAt());
        assertNull(saved.getCompletedAt());
        assertEquals("gemini-2.5-pro", saved.getModelIdentifier());
    }

    @Test
    @DisplayName("Verify CodeReview Uniqueness Constraint on (code_change_id, review_context_hash)")
    void testCodeReviewUniquenessConstraint() {
        String contextHash = "a1b2c3d4e5f67890123456789012345678901234567890123456789012345678";

        CodeReview review1 = new CodeReview();
        review1.setProject(testProject);
        review1.setRepository(testRepo);
        review1.setTask(testTask);
        review1.setCodeChange(testCodeChange);
        review1.setReviewContextHash(contextHash);
        review1.setAiInputHash("ai_input_hash_123");
        review1.setModelIdentifier("gemini-2.5-pro");
        codeReviewRepository.saveAndFlush(review1);

        CodeReview review2 = new CodeReview();
        review2.setProject(testProject);
        review2.setRepository(testRepo);
        review2.setTask(testTask);
        review2.setCodeChange(testCodeChange);
        review2.setReviewContextHash(contextHash);
        review2.setAiInputHash("ai_input_hash_123");
        review2.setModelIdentifier("gemini-2.5-pro");

        assertThrows(DataIntegrityViolationException.class, () -> {
            codeReviewRepository.saveAndFlush(review2);
        });
    }

    @Test
    @DisplayName("Verify ReviewFinding Entity Persistence and Relationships")
    void testReviewFindingPersistenceAndRelationships() {
        CodeReview review = new CodeReview();
        review.setProject(testProject);
        review.setRepository(testRepo);
        review.setTask(testTask);
        review.setCodeChange(testCodeChange);
        review.setReviewContextHash("1111111111222222222233333333334444444444555555555566666666667777");
        review.setModelIdentifier("gemini-2.5-pro");
        CodeReview savedReview = codeReviewRepository.save(review);

        ReviewFinding finding = new ReviewFinding();
        finding.setReview(savedReview);
        finding.setProject(testProject);
        finding.setRepository(testRepo);
        finding.setTask(testTask);
        finding.setCodeChange(testCodeChange);
        finding.setFindingType(FindingType.DETERMINISTIC_FINDING);
        finding.setCategory("SQL_INJECTION");
        finding.setCweId("CWE-89");
        finding.setSeverity(FindingSeverity.HIGH);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setTitle("Potential SQL Injection in Query Construction");
        finding.setDescription("String concatenation detected in raw SQL query building.");
        finding.setFilePath("src/main/java/com/example/UserService.java");
        finding.setStartLine(42);
        finding.setEndLine(45);
        finding.setSide(FindingSide.PROPOSED);
        finding.setContentHash("c672b8d00925700874c772c219614c244c4b6357492c68e18378ee0750537021");
        finding.setFingerprint("f890123456789012345678901234567890123456789012345678901234567890");
        finding.setRemediationGuidance("Use parameterized PreparedStatement queries.");
        finding.setStatus(FindingStatus.OPEN);

        ReviewFinding savedFinding = reviewFindingRepository.save(finding);

        assertNotNull(savedFinding.getId());
        assertEquals(FindingType.DETERMINISTIC_FINDING, savedFinding.getFindingType());
        assertEquals("CWE-89", savedFinding.getCweId());
        assertEquals(FindingSeverity.HIGH, savedFinding.getSeverity());
        assertEquals(FindingConfidence.HIGH, savedFinding.getConfidence());
        assertEquals(FindingSide.PROPOSED, savedFinding.getSide());
        assertEquals(FindingStatus.OPEN, savedFinding.getStatus());
        assertNotNull(savedFinding.getCreatedAt());
        assertNotNull(savedFinding.getUpdatedAt());
    }

    @Test
    @DisplayName("Verify CodeReviewRepository Query Methods")
    void testCodeReviewRepositoryQueries() {
        CodeReview review = new CodeReview();
        review.setProject(testProject);
        review.setRepository(testRepo);
        review.setTask(testTask);
        review.setCodeChange(testCodeChange);
        review.setReviewContextHash("2222222222333333333344444444445555555555666666666677777777778888");
        review.setModelIdentifier("gemini-2.5-pro");
        codeReviewRepository.save(review);

        List<CodeReview> byTask = codeReviewRepository.findByTask(testTask);
        assertFalse(byTask.isEmpty());
        assertEquals(1, byTask.size());

        List<CodeReview> byTaskId = codeReviewRepository.findByTaskId(testTask.getId());
        assertFalse(byTaskId.isEmpty());

        List<CodeReview> byCodeChange = codeReviewRepository.findByCodeChange(testCodeChange);
        assertFalse(byCodeChange.isEmpty());

        Optional<CodeReview> byHash = codeReviewRepository.findByCodeChangeIdAndReviewContextHash(
                testCodeChange.getId(), "2222222222333333333344444444445555555555666666666677777777778888");
        assertTrue(byHash.isPresent());

        List<CodeReview> byHierarchy = codeReviewRepository.findByProjectIdAndRepositoryIdAndTaskIdAndCodeChangeId(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId());
        assertEquals(1, byHierarchy.size());
    }

    @Test
    @DisplayName("Verify ReviewFindingRepository Query Methods and Fingerprint Lookups")
    void testReviewFindingRepositoryQueries() {
        CodeReview review = new CodeReview();
        review.setProject(testProject);
        review.setRepository(testRepo);
        review.setTask(testTask);
        review.setCodeChange(testCodeChange);
        review.setReviewContextHash("3333333333444444444455555555556666666666777777777788888888889999");
        review.setModelIdentifier("gemini-2.5-pro");
        CodeReview savedReview = codeReviewRepository.save(review);

        String fingerprint = "e123456789012345678901234567890123456789012345678901234567890123";

        ReviewFinding finding = new ReviewFinding();
        finding.setReview(savedReview);
        finding.setProject(testProject);
        finding.setRepository(testRepo);
        finding.setTask(testTask);
        finding.setCodeChange(testCodeChange);
        finding.setFindingType(FindingType.AI_FINDING);
        finding.setCategory("NULL_POINTER_DEREFERENCE");
        finding.setSeverity(FindingSeverity.MEDIUM);
        finding.setConfidence(FindingConfidence.HIGH);
        finding.setTitle("Potential NPE in UserService");
        finding.setDescription("User object checked for null after property dereference.");
        finding.setFilePath("src/main/java/com/example/UserService.java");
        finding.setStartLine(15);
        finding.setEndLine(20);
        finding.setSide(FindingSide.PROPOSED);
        finding.setContentHash("d789012345678901234567890123456789012345678901234567890123456789");
        finding.setFingerprint(fingerprint);
        finding.setStatus(FindingStatus.OPEN);
        reviewFindingRepository.save(finding);

        List<ReviewFinding> byReview = reviewFindingRepository.findByReview(savedReview);
        assertEquals(1, byReview.size());

        List<ReviewFinding> byReviewId = reviewFindingRepository.findByReviewId(savedReview.getId());
        assertEquals(1, byReviewId.size());

        List<ReviewFinding> byCodeChangeId = reviewFindingRepository.findByCodeChangeId(testCodeChange.getId());
        assertEquals(1, byCodeChangeId.size());

        List<ReviewFinding> byFingerprint = reviewFindingRepository.findByFingerprint(fingerprint);
        assertEquals(1, byFingerprint.size());

        Optional<ReviewFinding> byReviewAndFingerprint = reviewFindingRepository.findByReviewIdAndFingerprint(savedReview.getId(), fingerprint);
        assertTrue(byReviewAndFingerprint.isPresent());

        List<ReviewFinding> byProjectAndStatus = reviewFindingRepository.findByProjectIdAndStatus(testProject.getId(), FindingStatus.OPEN);
        assertEquals(1, byProjectAndStatus.size());

        List<ReviewFinding> byHierarchy = reviewFindingRepository.findByProjectIdAndRepositoryIdAndTaskIdAndCodeChangeId(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId());
        assertEquals(1, byHierarchy.size());
    }
}
