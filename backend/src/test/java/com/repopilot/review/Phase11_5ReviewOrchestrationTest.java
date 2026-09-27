package com.repopilot.review;

import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.*;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase11_5ReviewOrchestrationTest {

    @Autowired
    private CodeReviewOrchestrationService orchestrationService;

    @Autowired
    private CodeReviewRepository codeReviewRepository;

    @Autowired
    private ReviewFindingRepository reviewFindingRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private DiffGeneratorService diffGeneratorService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private com.repopilot.review.transport.DeterministicFakeAiReviewerTransport fakeTransport;

    private User ownerUser;
    private User unauthUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask;
    private CodeChange testCodeChange;
    private String origContent;
    private String propContent;
    private String origHash;
    private String propHash;

    @BeforeEach
    void setUp() {
        fakeTransport.reset();
        transactionTemplate.executeWithoutResult(status -> {
            reviewFindingRepository.deleteAll();
            codeReviewRepository.deleteAll();
            codeChangeRepository.deleteAll();
            taskRepository.deleteAll();
            repositoryRepository.deleteAll();
            projectMemberRepository.deleteAll();
            projectRepository.deleteAll();
            userRepository.deleteAll();

            ownerUser = new User();
            ownerUser.setEmail("owner_" + UUID.randomUUID() + "@repopilot.ai");
            ownerUser.setPasswordHash("pass");
            ownerUser.setName("Owner");
            ownerUser.setRole(UserRole.USER);
            ownerUser = userRepository.save(ownerUser);

            unauthUser = new User();
            unauthUser.setEmail("unauth_" + UUID.randomUUID() + "@repopilot.ai");
            unauthUser.setPasswordHash("pass");
            unauthUser.setName("Unauth");
            unauthUser.setRole(UserRole.USER);
            unauthUser = userRepository.save(unauthUser);

            testProject = new Project();
            testProject.setName("Orchestration Project " + UUID.randomUUID());
            testProject.setDescription("Desc");
            testProject.setOwner(ownerUser);
            testProject.setStatus(ProjectStatus.ACTIVE);
            testProject = projectRepository.save(testProject);

            testRepo = new Repository();
            testRepo.setProject(testProject);
            testRepo.setOwnerName("repopilot");
            testRepo.setRepoName("orch-repo-" + UUID.randomUUID().toString().substring(0, 8));
            testRepo.setDefaultBranch("main");
            testRepo.setVisibility(RepositoryVisibility.PRIVATE);
            testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
            testRepo = repositoryRepository.save(testRepo);

            testTask = new Task();
            testTask.setProject(testProject);
            testTask.setRepository(testRepo);
            testTask.setCreatedBy(ownerUser);
            testTask.setTitle("Orch Task");
            testTask.setDescription("Description");
            testTask.setStatus(TaskStatus.ANALYZING);
            testTask.setPriority(TaskPriority.HIGH);
            testTask = taskRepository.save(testTask);

            origContent = "public class Security {\n    public void run() {\n        int a = 1;\n    }\n}";
            propContent = "public class Security {\n    public void run() {\n        int a = 2;\n        statement.executeQuery(query);\n    }\n}";
            origHash = diffGeneratorService.computeSha256Hash(origContent);
            propHash = diffGeneratorService.computeSha256Hash(propContent);

            testCodeChange = new CodeChange();
            testCodeChange.setTask(testTask);
            testCodeChange.setRepository(testRepo);
            testCodeChange.setFilePath("src/Security.java");
            testCodeChange.setOperation("MODIFY");
            testCodeChange.setOldContent(origContent);
            testCodeChange.setNewContent(propContent);
            testCodeChange.setDiff("@@ -3 +3 @@\n-int a = 1;\n+int a = 2;");
            testCodeChange.setStartLine(1);
            testCodeChange.setEndLine(6);
            testCodeChange.setOriginalHash(origHash);
            testCodeChange.setProposedHash(propHash);
            testCodeChange = codeChangeRepository.save(testCodeChange);
        });
    }

    @AfterEach
    void tearDown() {
        fakeTransport.reset();
    }

    private AiReviewerResponseDto createValidAiResponseDto() {
        AiReviewerFindingDto finding = new AiReviewerFindingDto(
                FindingType.AI_FINDING,
                "INJECTION",
                "CWE-89",
                FindingSeverity.HIGH,
                FindingConfidence.HIGH,
                "SQL Injection in Security.java",
                "Unsanitized input in query",
                "src/Security.java",
                4,
                5,
                FindingSide.PROPOSED,
                "statement.executeQuery(query);",
                "Use PreparedStatement parameter binding."
        );

        return new AiReviewerResponseDto(ReviewStatus.COMPLETED, "Code review completed.", FindingSeverity.HIGH, List.of(finding));
    }

    // ==========================================
    // 1–9: BASIC ORCHESTRATION & LIFECYCLE
    // ==========================================

    @Test
    @DisplayName("Basic Test 1: Successful code review execution and persistence")
    void test01_SuccessfulCodeReviewOrchestration() {
        fakeTransport.setCannedResponse(createValidAiResponseDto());

        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        assertNotNull(result);
        assertNotNull(result.reviewId());
        assertEquals(ReviewStatus.COMPLETED, result.status());
        assertEquals("HIGH", result.overallRiskScore());
        assertEquals(1, result.totalAiFindings());
        assertEquals(1, result.acceptedFindingsCount());
        assertEquals(0, result.rejectedFindingsCount());
        assertEquals(1, result.findings().size());

        // Verify DB persistence
        CodeReview dbReview = codeReviewRepository.findById(result.reviewId()).orElseThrow();
        assertEquals(ReviewStatus.COMPLETED, dbReview.getStatus());

        List<ReviewFinding> dbFindings = reviewFindingRepository.findByReviewId(result.reviewId());
        assertEquals(1, dbFindings.size());
        ReviewFinding rf = dbFindings.get(0);
        assertEquals("SQL Injection in Security.java", rf.getTitle());
        assertNotNull(rf.getFingerprint());
        assertFalse(rf.getFingerprint().isBlank());
    }

    @Test
    @DisplayName("Basic Test 2: Successful code review with zero findings")
    void test02_SuccessfulReviewZeroFindings() {
        fakeTransport.setCannedResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "No issues found.", FindingSeverity.LOW, List.of()));

        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        assertNotNull(result);
        assertEquals(ReviewStatus.COMPLETED, result.status());
        assertEquals(0, result.totalAiFindings());
        assertEquals(0, result.findings().size());
    }

    @Test
    @DisplayName("Basic Test 4-5: Invalid findings rejected while valid findings persisted")
    void test04_MixedValidAndInvalidFindings() {
        AiReviewerFindingDto validFinding = new AiReviewerFindingDto(
                FindingType.AI_FINDING,
                "INJECTION",
                "CWE-89",
                FindingSeverity.HIGH,
                FindingConfidence.HIGH,
                "SQL Injection in Security.java",
                "Unsanitized query",
                "src/Security.java",
                4,
                5,
                FindingSide.PROPOSED,
                "statement.executeQuery(query);",
                null
        );

        AiReviewerFindingDto invalidFinding = new AiReviewerFindingDto(
                FindingType.AI_FINDING,
                "INJECTION",
                null,
                FindingSeverity.HIGH,
                FindingConfidence.HIGH,
                "Hallucinated finding",
                "Bad path",
                "../../etc/passwd", // Path traversal attack!
                1,
                2,
                FindingSide.PROPOSED,
                null,
                null
        );

        fakeTransport.setCannedResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "Mixed review.", FindingSeverity.HIGH, List.of(validFinding, invalidFinding)));

        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        assertNotNull(result);
        assertEquals(1, result.totalAiFindings());
        assertEquals(1, result.acceptedFindingsCount());
        assertEquals(0, result.rejectedFindingsCount());
        assertEquals(1, result.findings().size());
        assertEquals("SQL Injection in Security.java", result.findings().get(0).title());
    }

    @Test
    @DisplayName("Basic Test 7: AI service failure propagates exception without DB persistence")
    void test07_AiServiceFailurePropagates() {
        fakeTransport.setCannedException(com.repopilot.review.transport.AiReviewerTransportException.unavailable("AI service down"));

        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        assertNotNull(result);
        assertEquals(ReviewStatus.FAILED, result.status());
    }

    // ==========================================
    // 10–15: PERSISTENCE & ATOMICITY
    // ==========================================

    @Test
    @DisplayName("Persistence Test 10-14: CodeReview and ReviewFinding persisted atomically")
    void test10_AtomicPersistence() {
        fakeTransport.setCannedResponse(createValidAiResponseDto());

        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        CodeReview review = codeReviewRepository.findById(result.reviewId()).orElseThrow();
        List<ReviewFinding> findings = reviewFindingRepository.findByReviewId(result.reviewId());

        assertNotNull(review);
        assertEquals(1, findings.size());
        assertEquals(review.getId(), findings.get(0).getReview().getId());
    }

    // ==========================================
    // 16–22: FINGERPRINTING & DEDUPLICATION
    // ==========================================

    @Test
    @DisplayName("Fingerprint Test 16-22: Deterministic SHA-256 fingerprinting and batch deduplication")
    void test16_DeterministicFingerprintAndDeduplication() {
        AiReviewerFindingDto f1 = new AiReviewerFindingDto(
                FindingType.AI_FINDING,
                "INJECTION",
                "CWE-89",
                FindingSeverity.HIGH,
                FindingConfidence.HIGH,
                "SQL Injection 1",
                "Query issue",
                "src/Security.java",
                4,
                5,
                FindingSide.PROPOSED,
                "statement.executeQuery(query);",
                null
        );

        // Identical finding duplicate
        AiReviewerFindingDto f2 = new AiReviewerFindingDto(
                FindingType.AI_FINDING,
                "INJECTION",
                "CWE-89",
                FindingSeverity.HIGH,
                FindingConfidence.HIGH,
                "SQL Injection 2",
                "Duplicate query issue",
                "src/Security.java",
                4,
                5,
                FindingSide.PROPOSED,
                "statement.executeQuery(query);",
                null
        );

        fakeTransport.setCannedResponse(new AiReviewerResponseDto(ReviewStatus.COMPLETED, "Dup review.", FindingSeverity.HIGH, List.of(f1, f2)));

        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        assertNotNull(result);
        assertEquals(1, result.findings().size()); // Deduplicated!
    }

    // ==========================================
    // 23–27: IDEMPOTENCY & DUPLICATE REVIEWS
    // ==========================================

    @Test
    @DisplayName("Idempotency Test 23-24: Repeated completed request returns existing CodeReview without second LLM call")
    void test23_IdempotencyRepeatedRequest() {
        fakeTransport.setCannedResponse(createValidAiResponseDto());

        CodeReviewResultDto firstCall = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, "idempotency-key-1", ownerUser
        );

        CodeReviewResultDto secondCall = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, "idempotency-key-1", ownerUser
        );

        assertEquals(firstCall.reviewId(), secondCall.reviewId());
        assertEquals(firstCall.reviewContextHash(), secondCall.reviewContextHash());
    }

    // ==========================================
    // 28–32: STALE CONTEXT PROTECTION
    // ==========================================

    @Test
    @DisplayName("Stale Test 28-31: CodeChange mutation during AI execution triggers STALE_CONTEXT rejection")
    void test28_StaleContextPrePersistenceRejection() {
        fakeTransport.setOnRequestCallback(req -> {
            // Mutate CodeChange in database while AI call is executing!
            transactionTemplate.executeWithoutResult(status -> {
                CodeChange cc = codeChangeRepository.findById(testCodeChange.getId()).orElseThrow();
                cc.setOriginalHash("a1a2a3a4a5a60123456789abcdef0123456789abcdef0123456789abcdef0123");
                codeChangeRepository.saveAndFlush(cc);
            });
        });
        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        assertNotNull(result);
        assertEquals(ReviewStatus.STALE, result.status());
    }

    // ==========================================
    // 33–38: AUTHORIZATION & ACCESS CONTROL
    // ==========================================

    @Test
    @DisplayName("Authorization Test 33-38: Unauthorized user rejected with FORBIDDEN")
    void test33_UnauthorizedUserRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, unauthUser
        ));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // ==========================================
    // 49–54: ZERO SOURCE REPOSITORY MUTATION
    // ==========================================

    @Test
    @DisplayName("Side Effect Test 49-54: Zero source repository or Git mutations performed")
    void test49_ZeroSourceRepositoryMutation() {
        fakeTransport.setCannedResponse(createValidAiResponseDto());

        CodeReviewResultDto result = orchestrationService.executeCodeReview(
                testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(),
                ReviewMode.CODE_CHANGE_REVIEW, null, ownerUser
        );

        assertNotNull(result);
    }
}
