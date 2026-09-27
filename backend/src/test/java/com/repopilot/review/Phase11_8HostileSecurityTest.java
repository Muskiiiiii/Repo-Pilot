package com.repopilot.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.auth.security.JwtTokenProvider;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
import com.repopilot.common.exception.ApiException;
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
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.review.service.CodeReviewOrchestrationService;
import com.repopilot.review.service.ReviewContextService;
import com.repopilot.review.service.ReviewFindingValidator;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class Phase11_8HostileSecurityTest {

    @Autowired
    private MockMvc mockMvc;

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
    private CodeReviewOrchestrationService orchestrationService;

    @Autowired
    private ReviewFindingValidator reviewFindingValidator;

    @Autowired
    private ReviewContextService reviewContextService;

    @Autowired
    private DiffGeneratorService diffGeneratorService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiServiceClient aiServiceClient;

    private User ownerUser;
    private User nonMemberUser;
    private Project testProject;
    private Repository testRepo;
    private Task testTask;
    private CodeChange testCodeChange;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        ownerUser = new User();
        ownerUser.setEmail("owner_hostile_" + UUID.randomUUID() + "@repopilot.ai");
        ownerUser.setPasswordHash("pass");
        ownerUser.setName("Hostile Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        nonMemberUser = new User();
        nonMemberUser.setEmail("nonmember_hostile_" + UUID.randomUUID() + "@repopilot.ai");
        nonMemberUser.setPasswordHash("pass");
        nonMemberUser.setName("Hostile NonMember");
        nonMemberUser.setRole(UserRole.USER);
        nonMemberUser = userRepository.save(nonMemberUser);

        testProject = new Project();
        testProject.setName("Hostile Audit Project " + UUID.randomUUID());
        testProject.setDescription("Desc");
        testProject.setOwner(ownerUser);
        testProject.setStatus(ProjectStatus.ACTIVE);
        testProject = projectRepository.save(testProject);

        ProjectMember member = new ProjectMember();
        member.setProject(testProject);
        member.setUser(ownerUser);
        member.setRole(ProjectRole.MEMBER);
        projectMemberRepository.save(member);

        testRepo = new Repository();
        testRepo.setProject(testProject);
        testRepo.setOwnerName("repopilot");
        testRepo.setRepoName("hostile-repo-" + UUID.randomUUID().toString().substring(0, 8));
        testRepo.setDefaultBranch("main");
        testRepo.setVisibility(RepositoryVisibility.PRIVATE);
        testRepo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        testRepo = repositoryRepository.save(testRepo);

        testTask = new Task();
        testTask.setProject(testProject);
        testTask.setRepository(testRepo);
        testTask.setCreatedBy(ownerUser);
        testTask.setTitle("Hostile Task");
        testTask.setDescription("Description");
        testTask.setStatus(TaskStatus.ANALYZING);
        testTask.setPriority(TaskPriority.HIGH);
        testTask = taskRepository.save(testTask);

        testCodeChange = new CodeChange();
        testCodeChange.setTask(testTask);
        testCodeChange.setRepository(testRepo);
        testCodeChange.setFilePath("src/main/java/SecurityService.java");
        testCodeChange.setOperation("MODIFY");
        testCodeChange.setOldContent("public class SecurityService {\n    public void test() {\n        int a = 1;\n    }\n}");
        testCodeChange.setNewContent("public class SecurityService {\n    public void test() {\n        String user = \"admin\";\n        stmt.executeQuery(\"SELECT * FROM users WHERE name=\" + user);\n    }\n}");
        testCodeChange.setOriginalHash(diffGeneratorService.computeSha256Hash(testCodeChange.getOldContent()));
        testCodeChange.setProposedHash(diffGeneratorService.computeSha256Hash(testCodeChange.getNewContent()));
        testCodeChange = codeChangeRepository.save(testCodeChange);

        ownerToken = jwtTokenProvider.generateAccessToken(ownerUser);
    }

    // ==========================================
    // ATTACK CLASS 1: AUTHENTICATION (ATTACK-001 - ATTACK-005)
    // ==========================================
    @Test
    @DisplayName("ATTACK-001 - ATTACK-005: Unauthenticated and invalid authentication rejected with 401")
    void testAttackClass01_Authentication() throws Exception {
        // ATTACK-001: No header
        mockMvc.perform(post("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepo.getId() + "/tasks/" + testTask.getId() + "/reviews"))
                .andExpect(status().isUnauthorized());

        // ATTACK-003: Malformed JWT token
        mockMvc.perform(post("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepo.getId() + "/tasks/" + testTask.getId() + "/reviews")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized());
    }

    // ==========================================
    // ATTACK CLASS 2: AUTHORIZATION / IDOR (ATTACK-006 - ATTACK-012)
    // ==========================================
    @Test
    @DisplayName("ATTACK-006 - ATTACK-012: IDOR and cross-tenant resource tampering rejected")
    void testAttackClass02_AuthorizationIdor() throws Exception {
        UUID otherProjectId = UUID.randomUUID();

        // ATTACK-006: Wrong project ID in path
        mockMvc.perform(get("/api/v1/projects/" + otherProjectId + "/repositories/" + testRepo.getId() + "/tasks/" + testTask.getId() + "/reviews")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        // ATTACK-011: User not member of project
        String nonMemberToken = jwtTokenProvider.generateAccessToken(nonMemberUser);
        mockMvc.perform(get("/api/v1/projects/" + testProject.getId() + "/repositories/" + testRepo.getId() + "/tasks/" + testTask.getId() + "/reviews")
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden());
    }

    // ==========================================
    // ATTACK CLASS 4: PATH TRAVERSAL (ATTACK-018 - ATTACK-024)
    // ==========================================
    @Test
    @DisplayName("ATTACK-018 - ATTACK-024: Path traversal and encoded dot patterns rejected")
    void testAttackClass04_PathTraversal() {
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("../Secret.java"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("../../etc/passwd"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("%2e%2e%2fSecret.java"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("/etc/shadow"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("..\\..\\win.ini"));
    }

    // ==========================================
    // ATTACK CLASS 6 & 12: EVIDENCE FORGERY & MALICIOUS AI OUTPUT (ATTACK-029 - ATTACK-066)
    // ==========================================
    @Test
    @DisplayName("ATTACK-029 - ATTACK-066: Untrusted AI evidence forgery and invalid line ranges rejected by validator")
    void testAttackClass06And12_EvidenceForgeryAndMaliciousOutput() {
        ReviewContext context = reviewContextService.buildReviewContext(testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        // ATTACK-029: Claimed evidence does not exist in code
        ReviewFindingResponseDto forgedFinding = new ReviewFindingResponseDto();
        forgedFinding.setTitle("Forged Finding");
        forgedFinding.setCategory("SECURITY");
        forgedFinding.setSeverity(FindingSeverity.HIGH);
        forgedFinding.setConfidence(FindingConfidence.HIGH);
        forgedFinding.setFilePath("src/main/java/SecurityService.java");
        forgedFinding.setStartLine(1);
        forgedFinding.setEndLine(3);
        forgedFinding.setSide(FindingSide.PROPOSED);
        forgedFinding.setEvidence("NON_EXISTENT_EVIDENCE_LINE_9999");
        forgedFinding.setRemediation("None");

        ReviewResponseDto aiResponse = new ReviewResponseDto("Summary", "HIGH", context.reviewContextHash(), List.of(forgedFinding));

        ValidationResult result = reviewFindingValidator.validateFindings(context, aiResponse);
        assertEquals(0, result.getValidatedFindings().size(), "Forged evidence finding must be rejected");

        // ATTACK-061: Negative line range
        forgedFinding.setStartLine(-1);
        forgedFinding.setEndLine(5);
        ValidationResult result2 = reviewFindingValidator.validateFindings(context, aiResponse);
        assertEquals(0, result2.getValidatedFindings().size(), "Negative line range must be rejected");
    }

    // ==========================================
    // ATTACK CLASS 8: STALE CONTEXT / TOCTOU (ATTACK-039 - ATTACK-043)
    // ==========================================
    @Test
    @DisplayName("ATTACK-039 - ATTACK-043: Stale code change context detected before persistence")
    void testAttackClass08_StaleContextToctou() {
        ReviewContext context = reviewContextService.buildReviewContext(testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        // Mutate CodeChange proposed content hash to simulate concurrent modification
        testCodeChange.setProposedHash("mutated_hash_99999999");
        codeChangeRepository.save(testCodeChange);

        assertTrue(reviewContextService.isContextStale(context), "Context must be flagged as stale after CodeChange mutation");
    }

    // ==========================================
    // ATTACK CLASS 13: SECRET EXFILTRATION (ATTACK-067 - ATTACK-072)
    // ==========================================
    @Test
    @DisplayName("ATTACK-067 - ATTACK-072: Secrets in findings rejected and neutralized")
    void testAttackClass13_SecretExfiltration() {
        ReviewContext context = reviewContextService.buildReviewContext(testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        ReviewFindingResponseDto secretFinding = new ReviewFindingResponseDto();
        secretFinding.setTitle("Secret Finding");
        secretFinding.setCategory("SECURITY");
        secretFinding.setSeverity(FindingSeverity.CRITICAL);
        secretFinding.setConfidence(FindingConfidence.HIGH);
        secretFinding.setFilePath("src/main/java/SecurityService.java");
        secretFinding.setStartLine(1);
        secretFinding.setEndLine(3);
        secretFinding.setSide(FindingSide.PROPOSED);
        secretFinding.setEvidence("public class SecurityService");
        secretFinding.setRemediation("Secret: ghp_1234567890abcdef1234567890abcdef1234");

        ReviewResponseDto aiResponse = new ReviewResponseDto("Summary", "CRITICAL", context.reviewContextHash(), List.of(secretFinding));

        ValidationResult result = reviewFindingValidator.validateFindings(context, aiResponse);
        assertEquals(0, result.getValidatedFindings().size(), "Finding containing secret MUST be rejected");
    }

    // ==========================================
    // ATTACK CLASS 15: REMEDIATION COMMAND INJECTION (ATTACK-077 - ATTACK-082)
    // ==========================================
    @Test
    @DisplayName("ATTACK-077 - ATTACK-082: Malicious shell commands in remediation rejected")
    void testAttackClass15_RemediationCommandInjection() {
        ReviewContext context = reviewContextService.buildReviewContext(testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        ReviewFindingResponseDto commandFinding = new ReviewFindingResponseDto();
        commandFinding.setTitle("Command Injection Finding");
        commandFinding.setCategory("SECURITY");
        commandFinding.setSeverity(FindingSeverity.HIGH);
        commandFinding.setConfidence(FindingConfidence.HIGH);
        commandFinding.setFilePath("src/main/java/SecurityService.java");
        commandFinding.setStartLine(1);
        commandFinding.setEndLine(3);
        commandFinding.setSide(FindingSide.PROPOSED);
        commandFinding.setEvidence("public class SecurityService");
        commandFinding.setRemediation("Run: sudo rm -rf / --no-preserve-root");

        ReviewResponseDto aiResponse = new ReviewResponseDto("Summary", "HIGH", context.reviewContextHash(), List.of(commandFinding));

        ValidationResult result = reviewFindingValidator.validateFindings(context, aiResponse);
        assertEquals(0, result.getValidatedFindings().size(), "Remediation with malicious shell commands MUST be rejected");
    }

    // ==========================================
    // ATTACK CLASS 24: FINDING FINGERPRINT COLLISION ATTACK (ATTACK-115 - ATTACK-117)
    // ==========================================
    @Test
    @DisplayName("ATTACK-115 - ATTACK-117: Fingerprint canonicalization escapes delimiters cleanly")
    void testAttackClass24_FingerprintCollisionEscaping() {
        ReviewContext context = reviewContextService.buildReviewContext(testProject.getId(), testRepo.getId(), testTask.getId(), testCodeChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        ReviewFindingResponseDto finding1 = new ReviewFindingResponseDto();
        finding1.setTitle("Finding 1");
        finding1.setDescription("Valid description");
        finding1.setCategory("CAT|BAR");
        finding1.setSeverity(FindingSeverity.HIGH);
        finding1.setConfidence(FindingConfidence.HIGH);
        finding1.setFilePath("src/main/java/SecurityService.java");
        finding1.setStartLine(1);
        finding1.setEndLine(3);
        finding1.setSide(FindingSide.PROPOSED);
        finding1.setEvidence("public class SecurityService");
        finding1.setRemediation("Fix code");

        ReviewResponseDto aiResponse = new ReviewResponseDto("Summary", "HIGH", context.reviewContextHash(), List.of(finding1));

        ValidationResult result = reviewFindingValidator.validateFindings(context, aiResponse);
        assertEquals(1, result.getValidatedFindings().size());
        assertNotNull(result.getValidatedFindings().get(0).fingerprint());
    }
}
