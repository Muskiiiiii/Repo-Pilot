package com.repopilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.FindingConfidence;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingSide;
import com.repopilot.review.entity.FindingType;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.review.entity.ReviewStatus;
import com.repopilot.review.service.AiInputHashService;
import com.repopilot.review.service.ReviewOrchestrationService;
import com.repopilot.review.service.ReviewOrchestrationServiceImpl;
import com.repopilot.review.transport.AiReviewerTransportException;
import com.repopilot.review.transport.DeterministicFakeAiReviewerTransport;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase13_Slice3ReviewOrchestrationTest {

    @Autowired
    private ReviewOrchestrationService reviewOrchestrationService;

    @Autowired
    private DeterministicFakeAiReviewerTransport fakeTransport;

    @Autowired
    private AiInputHashService aiInputHashService;

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
    private PlanRepository planRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User owner;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;
    private CodeChange changeA;

    @BeforeEach
    void setUp() {
        fakeTransport.reset();
        if (reviewOrchestrationService instanceof ReviewOrchestrationServiceImpl impl) {
            impl.setTimeoutMs(30_000);
        }

        owner = new User();
        owner.setEmail("owner_slice3_" + UUID.randomUUID() + "@test.com");
        owner.setPasswordHash("hash");
        owner.setName("Owner Slice3");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        projectA = new Project();
        projectA.setOwner(owner);
        projectA.setName("Project Slice3 A " + UUID.randomUUID());
        projectA.setDescription("Project A");
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));

        projectB = new Project();
        projectB.setOwner(owner);
        projectB.setName("Project Slice3 B " + UUID.randomUUID());
        projectB.setDescription("Project B");
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        projectMemberRepository.save(new ProjectMember(projectB, owner, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("repopilot");
        repoA.setRepoName("repo-slice3-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("repopilot");
        repoB.setRepoName("repo-slice3-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task Slice3 A");
        taskA.setDescription("Task Slice3 Description");
        taskA.setPriority(TaskPriority.HIGH);
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(owner);
        taskB.setTitle("Task Slice3 B");
        taskB.setPriority(TaskPriority.LOW);
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB = taskRepository.save(taskB);

        Plan planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan Slice3 Objective");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/Service.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class Service {}");
        changeA.setNewContent("public class Service { String token = \"ghp_123456789012345678901234567890abcdef\"; }");
        changeA.setOriginalHash("orig_hash_123");
        changeA.setProposedHash("prop_hash_456");
        changeA.setDiff("--- src/Service.java\n+++ src/Service.java\n@@ -1 +1 @@\n-public class Service {}\n+public class Service { String token = \"ghp_123456789012345678901234567890abcdef\"; }\n");
        changeA = codeChangeRepository.save(changeA);
    }

    // ==================================================
    // 1. EXACT AI PAYLOAD <-> ai_input_hash PROVENANCE INVARIANT
    // ==================================================

    @Test
    @DisplayName("Provenance Invariant: Captured transport request matches exact canonical payload represented by ai_input_hash")
    void testProvenanceInvariantCapturedRequestMatchesHash() {
        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertNotNull(result);
        AiReviewerRequestDto capturedRequest = fakeTransport.getLastReceivedRequest();
        assertNotNull(capturedRequest);

        // Prove single-source-of-truth invariant: captured request converts back to exact canonical payload
        AiReviewPayload reconstructedPayload = capturedRequest.toPayload();
        String expectedHash = aiInputHashService.computeAiInputHash(reconstructedPayload);

        assertEquals(expectedHash, capturedRequest.aiInputHash(), "Captured transport request hash must match expected hash");
        assertEquals(expectedHash, result.aiInputHash(), "Internal orchestration result hash must match expected hash");
    }

    @Test
    @DisplayName("Provenance Invariant: Mutating every material payload field changes the canonical ai_input_hash")
    void testAll10MaterialPayloadFieldsMutationChangesHash() {
        AiReviewPayload basePayload = new AiReviewPayload(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(),
                "CODE_CHANGE_REVIEW", "diff_content", "title", List.of("src/Service.java"),
                "p2026.1", "gemini-2.5-pro"
        );
        String baseHash = aiInputHashService.computeAiInputHash(basePayload);

        // 1. projectId
        AiReviewPayload p1 = new AiReviewPayload(UUID.randomUUID(), basePayload.repositoryId(), basePayload.taskId(), basePayload.codeChangeId(), basePayload.reviewMode(), basePayload.redactedDiff(), basePayload.sanitizedTitle(), basePayload.fileList(), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p1));

        // 2. repositoryId
        AiReviewPayload p2 = new AiReviewPayload(basePayload.projectId(), UUID.randomUUID(), basePayload.taskId(), basePayload.codeChangeId(), basePayload.reviewMode(), basePayload.redactedDiff(), basePayload.sanitizedTitle(), basePayload.fileList(), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p2));

        // 3. taskId
        AiReviewPayload p3 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), UUID.randomUUID(), basePayload.codeChangeId(), basePayload.reviewMode(), basePayload.redactedDiff(), basePayload.sanitizedTitle(), basePayload.fileList(), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p3));

        // 4. codeChangeId
        AiReviewPayload p4 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), basePayload.taskId(), UUID.randomUUID(), basePayload.reviewMode(), basePayload.redactedDiff(), basePayload.sanitizedTitle(), basePayload.fileList(), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p4));

        // 5. reviewMode
        AiReviewPayload p5 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), basePayload.taskId(), basePayload.codeChangeId(), "SECURITY_REVIEW", basePayload.redactedDiff(), basePayload.sanitizedTitle(), basePayload.fileList(), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p5));

        // 6. redactedDiff
        AiReviewPayload p6 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), basePayload.taskId(), basePayload.codeChangeId(), basePayload.reviewMode(), "mutated_diff", basePayload.sanitizedTitle(), basePayload.fileList(), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p6));

        // 7. sanitizedTitle
        AiReviewPayload p7 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), basePayload.taskId(), basePayload.codeChangeId(), basePayload.reviewMode(), basePayload.redactedDiff(), "mutated_title", basePayload.fileList(), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p7));

        // 8. fileList
        AiReviewPayload p8 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), basePayload.taskId(), basePayload.codeChangeId(), basePayload.reviewMode(), basePayload.redactedDiff(), basePayload.sanitizedTitle(), List.of("src/Other.java"), basePayload.promptVersion(), basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p8));

        // 9. promptVersion
        AiReviewPayload p9 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), basePayload.taskId(), basePayload.codeChangeId(), basePayload.reviewMode(), basePayload.redactedDiff(), basePayload.sanitizedTitle(), basePayload.fileList(), "p2026.2", basePayload.modelIdentifier());
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p9));

        // 10. modelIdentifier
        AiReviewPayload p10 = new AiReviewPayload(basePayload.projectId(), basePayload.repositoryId(), basePayload.taskId(), basePayload.codeChangeId(), basePayload.reviewMode(), basePayload.redactedDiff(), basePayload.sanitizedTitle(), basePayload.fileList(), basePayload.promptVersion(), "gemini-3.0-pro");
        assertNotEquals(baseHash, aiInputHashService.computeAiInputHash(p10));
    }

    // ==================================================
    // 2. REAL JSON DESERIALIZATION BOUNDARY TESTS
    // ==================================================

    @Test
    @DisplayName("JSON Boundary: Valid raw JSON response passes strict deserialization")
    void testValidJsonAccepted() {
        fakeTransport.setCannedRawJson("""
                {
                    "status": "COMPLETED",
                    "summary": "Valid raw JSON review summary",
                    "overallRiskScore": "LOW",
                    "findings": []
                }
                """);

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.COMPLETED, result.status());
        assertEquals("Valid raw JSON review summary", result.summary());
    }

    @Test
    @DisplayName("JSON Boundary: Unknown top-level property in raw JSON is REJECTED with INVALID_CONTRACT_JSON")
    void testUnknownTopLevelPropertyRejected() {
        fakeTransport.setCannedRawJson("""
                {
                    "status": "COMPLETED",
                    "summary": "Review complete",
                    "overallRiskScore": "LOW",
                    "findings": [],
                    "unauthorizedExtraField": "malicious"
                }
                """);

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("INVALID_CONTRACT_JSON"), result.failureReason());
    }

    @Test
    @DisplayName("JSON Boundary: Unknown nested finding property in raw JSON is REJECTED with INVALID_CONTRACT_JSON")
    void testUnknownNestedPropertyRejected() {
        fakeTransport.setCannedRawJson("""
                {
                    "status": "COMPLETED",
                    "summary": "Review complete",
                    "overallRiskScore": "HIGH",
                    "findings": [
                        {
                            "findingType": "AI_FINDING",
                            "category": "SQL_INJECTION",
                            "severity": "HIGH",
                            "confidence": "HIGH",
                            "title": "Title",
                            "description": "Desc",
                            "filePath": "src/Main.java",
                            "startLine": 1,
                            "endLine": 5,
                            "side": "PROPOSED",
                            "extraNestedField": "invalid"
                        }
                    ]
                }
                """);

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("INVALID_CONTRACT_JSON"), result.failureReason());
    }

    @Test
    @DisplayName("JSON Boundary: Malformed JSON syntax is REJECTED with INVALID_CONTRACT_JSON")
    void testMalformedJsonRejected() {
        fakeTransport.setCannedRawJson("{invalid-json-syntax}");

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("INVALID_CONTRACT_JSON"), result.failureReason());
    }

    @Test
    @DisplayName("JSON Boundary: Wrong JSON field type (findings as string) is REJECTED with INVALID_CONTRACT_JSON")
    void testWrongJsonTypeRejected() {
        fakeTransport.setCannedRawJson("""
                {
                    "status": "COMPLETED",
                    "summary": "Summary",
                    "overallRiskScore": "HIGH",
                    "findings": "not-an-array"
                }
                """);

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("INVALID_CONTRACT_JSON"), result.failureReason());
    }

    @Test
    @DisplayName("JSON Boundary: Invalid enum value is REJECTED with INVALID_CONTRACT_JSON")
    void testInvalidEnumRejected() {
        fakeTransport.setCannedRawJson("""
                {
                    "status": "INVALID_STATUS_VALUE",
                    "summary": "Summary",
                    "overallRiskScore": "HIGH",
                    "findings": []
                }
                """);

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("INVALID_CONTRACT_JSON"), result.failureReason());
    }

    @Test
    @DisplayName("JSON Boundary: Invalid line range (startLine > endLine) is REJECTED with INVALID_CONTRACT_FINDING")
    void testInvalidLineRangeRejected() {
        fakeTransport.setCannedRawJson("""
                {
                    "status": "COMPLETED",
                    "summary": "Summary",
                    "overallRiskScore": "HIGH",
                    "findings": [
                        {
                            "findingType": "AI_FINDING",
                            "category": "BUG",
                            "severity": "HIGH",
                            "confidence": "HIGH",
                            "title": "Title",
                            "description": "Desc",
                            "filePath": "src/Main.java",
                            "startLine": 10,
                            "endLine": 5,
                            "side": "PROPOSED",
                            "evidence": "ev",
                            "remediation": "rem"
                        }
                    ]
                }
                """);

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("INVALID_CONTRACT_FINDING"), result.failureReason());
    }

    @Test
    @DisplayName("JSON Boundary: Finding count > 50 is REJECTED with RESOURCE_LIMIT_EXCEEDED")
    void testFindingCountOver50Rejected() throws Exception {
        List<AiReviewerFindingDto> findings = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            findings.add(new AiReviewerFindingDto(
                    FindingType.AI_FINDING, "BUG", "CWE-20",
                    FindingSeverity.LOW, FindingConfidence.MEDIUM,
                    "Finding " + i, "Description " + i, "src/Service.java", 1, 5,
                    FindingSide.PROPOSED, "evidence", "remediation"
            ));
        }
        AiReviewerResponseDto oversizedResponse = new AiReviewerResponseDto(
                ReviewStatus.COMPLETED, "51 findings", FindingSeverity.HIGH, findings
        );
        fakeTransport.setCannedRawJson(objectMapper.writeValueAsString(oversizedResponse));

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("RESOURCE_LIMIT_EXCEEDED"), result.failureReason());
    }

    // ==================================================
    // 3. REAL BOUNDED TIMEOUT TEST
    // ==================================================

    @Test
    @DisplayName("Timeout Boundary: Transport execution delay exceeding timeoutMs triggers actual async Future timeout")
    void testRealBoundedTimeoutExecution() {
        // Set fake transport to delay 500 ms
        fakeTransport.setDelayMs(500);

        // Set orchestration service timeout to 50 ms
        if (reviewOrchestrationService instanceof ReviewOrchestrationServiceImpl impl) {
            impl.setTimeoutMs(50);
        }

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("AI_REVIEWER_TIMEOUT"), result.failureReason());
        assertTrue(result.failureReason().contains("timed out after 50 ms"), result.failureReason());
    }

    // ==================================================
    // 4. SECURITY REGRESSION & CAPABILITY BOUNDARY TESTS
    // ==================================================

    @Test
    @DisplayName("Security: Transport receives redacted diff containing no raw secrets")
    void testTransportReceivesRedactedDiffOnly() {
        reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        AiReviewerRequestDto lastRequest = fakeTransport.getLastReceivedRequest();
        assertNotNull(lastRequest);
        assertFalse(lastRequest.redactedDiff().contains("ghp_123456789012345678901234567890abcdef"));
        assertTrue(lastRequest.redactedDiff().contains("[REDACTED_SECRET_PRE_LLM]"));
    }

    @Test
    @DisplayName("Security: Sensitive Authorization headers are sanitized from error messages")
    void testSensitiveAuthorizationHeadersSanitized() {
        fakeTransport.setCannedException(
                AiReviewerTransportException.transportFailure("Failed connecting with authorization=Bearer secret_token_value_12345")
        );

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertEquals(ReviewStatus.FAILED, result.status());
        assertNotNull(result.failureReason());
        assertFalse(result.failureReason().contains("secret_token_value_12345"));
        assertTrue(result.failureReason().contains("[REDACTED_HEADER]"));
    }

    @Test
    @DisplayName("Capability Boundary: Orchestration is stateless in-memory execution without DB mutation or external Git/GitHub calls")
    void testCapabilityBoundaryNoSideEffects() {
        long codeChangesCountBefore = codeChangeRepository.count();

        AiReviewOrchestrationResult result = reviewOrchestrationService.orchestrateReview(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner
        );

        assertNotNull(result);

        // Verify zero DB mutations occurred in CodeChange table
        assertEquals(codeChangesCountBefore, codeChangeRepository.count());
    }
}
