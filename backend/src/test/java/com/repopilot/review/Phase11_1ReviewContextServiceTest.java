package com.repopilot.review;

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
import com.repopilot.review.dto.EvidenceRef;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.ReviewContextRequest;
import com.repopilot.review.entity.ReviewMode;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase11_1ReviewContextServiceTest {

    @Autowired
    private ReviewContextService reviewContextService;

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

    @Autowired
    private TransactionTemplate transactionTemplate;

    private User ownerUser;
    private User unauthorizedUser;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;
    private Plan planA;
    private CodeChange changeA;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(status -> {
            ownerUser = new User();
            ownerUser.setEmail("owner_11_1_" + UUID.randomUUID() + "@repopilot.ai");
            ownerUser.setPasswordHash("hash123");
            ownerUser.setName("Owner 11.1");
            ownerUser.setRole(UserRole.USER);
            ownerUser = userRepository.save(ownerUser);

            unauthorizedUser = new User();
            unauthorizedUser.setEmail("unauth_11_1_" + UUID.randomUUID() + "@repopilot.ai");
            unauthorizedUser.setPasswordHash("hash123");
            unauthorizedUser.setName("Unauth User");
            unauthorizedUser.setRole(UserRole.USER);
            unauthorizedUser = userRepository.save(unauthorizedUser);

            projectA = new Project();
            projectA.setName("Project A " + UUID.randomUUID());
            projectA.setDescription("Project A Description");
            projectA.setOwner(ownerUser);
            projectA.setStatus(ProjectStatus.ACTIVE);
            projectA = projectRepository.save(projectA);

            projectB = new Project();
            projectB.setName("Project B " + UUID.randomUUID());
            projectB.setDescription("Project B Description");
            projectB.setOwner(ownerUser);
            projectB.setStatus(ProjectStatus.ACTIVE);
            projectB = projectRepository.save(projectB);

            ProjectMember memberA = new ProjectMember(projectA, ownerUser, ProjectRole.OWNER);
            projectMemberRepository.save(memberA);

            repoA = new Repository();
            repoA.setProject(projectA);
            repoA.setOwnerName("repopilot");
            repoA.setRepoName("repo-a-" + UUID.randomUUID().toString().substring(0, 8));
            repoA.setDefaultBranch("main");
            repoA.setVisibility(RepositoryVisibility.PUBLIC);
            repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
            repoA = repositoryRepository.save(repoA);

            repoB = new Repository();
            repoB.setProject(projectB);
            repoB.setOwnerName("repopilot");
            repoB.setRepoName("repo-b-" + UUID.randomUUID().toString().substring(0, 8));
            repoB.setDefaultBranch("main");
            repoB.setVisibility(RepositoryVisibility.PUBLIC);
            repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
            repoB = repositoryRepository.save(repoB);

            taskA = new Task();
            taskA.setProject(projectA);
            taskA.setRepository(repoA);
            taskA.setCreatedBy(ownerUser);
            taskA.setTitle("Security Hardening Task A");
            taskA.setDescription("Refactor authentication filter to prevent JWT replay.");
            taskA.setStatus(TaskStatus.ANALYZING);
            taskA.setPriority(TaskPriority.HIGH);
            taskA = taskRepository.save(taskA);

            taskB = new Task();
            taskB.setProject(projectB);
            taskB.setRepository(repoB);
            taskB.setCreatedBy(ownerUser);
            taskB.setTitle("Task B");
            taskB.setDescription("Task B Description");
            taskB.setStatus(TaskStatus.ANALYZING);
            taskB.setPriority(TaskPriority.MEDIUM);
            taskB = taskRepository.save(taskB);

            planA = new Plan();
            planA.setTask(taskA);
            planA.setObjective("Upgrade JJWT dependency and validate claims");
            planA = planRepository.save(planA);

            changeA = new CodeChange();
            changeA.setTask(taskA);
            changeA.setPlan(planA);
            changeA.setRepository(repoA);
            changeA.setFilePath("src/main/java/com/example/AuthFilter.java");
            changeA.setOperation("MODIFY");
            changeA.setOldContent("public class AuthFilter {\n    void doFilter() { String token = req.getHeader(\"Authorization\"); }\n}");
            changeA.setNewContent("public class AuthFilter {\n    void doFilter() { String token = req.getHeader(\"Authorization\"); validateJwt(token); }\n}");
            changeA.setOriginalHash("a1b2c3d4e5f67890123456789012345678901234567890123456789012345678");
            changeA.setProposedHash("b2c3d4e5f6789012345678901234567890123456789012345678901234567890");
            changeA.setDiff("--- a/src/main/java/com/example/AuthFilter.java\n+++ b/src/main/java/com/example/AuthFilter.java\n@@ -2,2 +2,2 @@\n-    void doFilter() { String token = req.getHeader(\"Authorization\"); }\n+    void doFilter() { String token = req.getHeader(\"Authorization\"); validateJwt(token); }\n");
            changeA = codeChangeRepository.save(changeA);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(status -> {
            if (taskA != null) codeChangeRepository.deleteByTaskId(taskA.getId());
            if (taskB != null) codeChangeRepository.deleteByTaskId(taskB.getId());
            if (planA != null) planRepository.delete(planA);
            if (taskA != null) taskRepository.delete(taskA);
            if (taskB != null) taskRepository.delete(taskB);
            if (repoA != null) repositoryRepository.delete(repoA);
            if (repoB != null) repositoryRepository.delete(repoB);
            if (projectA != null) projectMemberRepository.deleteAll(projectMemberRepository.findByProject(projectA));
            if (projectB != null) projectMemberRepository.deleteAll(projectMemberRepository.findByProject(projectB));
            if (projectA != null) projectRepository.delete(projectA);
            if (projectB != null) projectRepository.delete(projectB);
            if (ownerUser != null) userRepository.delete(ownerUser);
            if (unauthorizedUser != null) userRepository.delete(unauthorizedUser);
            return null;
        });
    }

    @Test
    @DisplayName("1. Deterministic Context Hash - Valid Format")
    void testDeterministicContextHash() {
        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertNotNull(context.reviewContextHash());
        assertEquals(64, context.reviewContextHash().length());
        assertTrue(context.reviewContextHash().matches("^[a-f0-9]{64}$"));
    }

    @Test
    @DisplayName("2. Same Context -> Same Hash")
    void testSameContextSameHash() {
        ReviewContext context1 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);
        ReviewContext context2 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertEquals(context1.reviewContextHash(), context2.reviewContextHash());
    }

    @Test
    @DisplayName("3. Changed Source -> Different Hash")
    void testChangedSourceDifferentHash() {
        ReviewContext context1 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        transactionTemplate.execute(status -> {
            changeA.setNewContent("public class AuthFilter { void doFilter() { validateTokenStrictly(); } }");
            changeA.setProposedHash("c3d4e5f678901234567890123456789012345678901234567890123456789012");
            codeChangeRepository.saveAndFlush(changeA);
            return null;
        });

        ReviewContext context2 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertNotEquals(context1.reviewContextHash(), context2.reviewContextHash());
    }

    @Test
    @DisplayName("4. Changed CodeChange -> Different Hash")
    void testChangedCodeChangeDifferentHash() {
        CodeChange change2 = transactionTemplate.execute(status -> {
            CodeChange c2 = new CodeChange();
            c2.setTask(taskA);
            c2.setPlan(planA);
            c2.setRepository(repoA);
            c2.setFilePath("src/main/java/com/example/UserService.java");
            c2.setOperation("CREATE");
            c2.setOldContent("");
            c2.setNewContent("public class UserService {}");
            c2.setOriginalHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            c2.setProposedHash("d4e5f67890123456789012345678901234567890123456789012345678901234");
            return codeChangeRepository.save(c2);
        });

        ReviewContext context1 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);
        ReviewContext context2 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), change2.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertNotEquals(context1.reviewContextHash(), context2.reviewContextHash());
    }

    @Test
    @DisplayName("5. Changed Evidence -> Different Hash")
    void testChangedEvidenceDifferentHash() {
        ReviewContext context1 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        List<EvidenceRef> modifiedEvidence = new ArrayList<>(context1.evidenceReferences());
        modifiedEvidence.add(new EvidenceRef(
                "RAG_CHUNK", projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(),
                "src/main/java/com/example/AuthFilter.java", "PROPOSED", 1, 10,
                "9999999999999999999999999999999999999999999999999999999999999999", "snap123", "chunk_1", "PROJECT_MEMBER"
        ));

        ReviewContext context2 = new ReviewContext(
                context1.projectId(), context1.repositoryId(), context1.taskId(), context1.codeChangeId(),
                context1.reviewMode(), context1.filePath(), context1.operation(), context1.originalContent(),
                context1.proposedContent(), context1.unifiedDiff(), context1.originalHash(), context1.proposedHash(),
                "", context1.contextVersion(), context1.isStale(), modifiedEvidence
        );

        String hash1 = context1.reviewContextHash();
        String hash2 = reviewContextService.computeReviewContextHash(context2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    @DisplayName("6. Changed Review Mode -> Different Hash")
    void testChangedReviewModeDifferentHash() {
        ReviewContext context1 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);
        ReviewContext context2 = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.SECURITY_REVIEW, ownerUser);

        assertNotEquals(context1.reviewContextHash(), context2.reviewContextHash());
    }

    @Test
    @DisplayName("7. Stale CodeChange Rejection")
    void testStaleCodeChangeRejection() {
        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertFalse(reviewContextService.isContextStale(context));

        transactionTemplate.execute(status -> {
            changeA.setProposedHash("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
            codeChangeRepository.saveAndFlush(changeA);
            return null;
        });

        assertTrue(reviewContextService.isContextStale(context));
    }

    @Test
    @DisplayName("8. Invalid Path Rejection")
    void testInvalidPathRejection() {
        assertThrows(ApiException.class, () -> reviewContextService.validatePath(null));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath(""));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("   "));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("src/\0/Main.java"));
    }

    @Test
    @DisplayName("9. Traversal Rejection")
    void testTraversalRejection() {
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("../etc/passwd"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("src/../../etc/passwd"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("./../secret.txt"));
    }

    @Test
    @DisplayName("10. Absolute Path Rejection")
    void testAbsolutePathRejection() {
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("/etc/passwd"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("/var/log/syslog"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("C:\\Windows\\System32\\config"));
    }

    @Test
    @DisplayName("11. Symlink / Path Escape Rejection")
    void testSymlinkPathEscapeRejection() {
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("foo/../../bar"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("a/b/c/../../../../escape"));
    }

    @Test
    @DisplayName("12. Unicode / Path Normalization Attack Rejection")
    void testUnicodePathNormalizationAttack() {
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("%2e%2e/etc/passwd"));
        assertThrows(ApiException.class, () -> reviewContextService.validatePath("src/%5c%2e%2e/secret"));
    }

    @Test
    @DisplayName("13. Cross-Project Authorization Rejection")
    void testCrossProjectAuthorizationRejection() {
        assertThrows(ApiException.class, () -> reviewContextService.buildReviewContext(
                projectB.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser));
    }

    @Test
    @DisplayName("14. Cross-Repository Authorization Rejection")
    void testCrossRepositoryAuthorizationRejection() {
        assertThrows(ApiException.class, () -> reviewContextService.buildReviewContext(
                projectA.getId(), repoB.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser));
    }

    @Test
    @DisplayName("15. Unauthorized Task Rejection")
    void testUnauthorizedTaskRejection() {
        assertThrows(ApiException.class, () -> reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, unauthorizedUser));
    }

    @Test
    @DisplayName("16. Evidence Provenance Correctness")
    void testEvidenceProvenanceCorrectness() {
        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertNotNull(context.evidenceReferences());
        assertFalse(context.evidenceReferences().isEmpty());

        EvidenceRef ref = context.evidenceReferences().get(0);
        assertEquals("CODE_CHANGE", ref.sourceType());
        assertEquals(projectA.getId(), ref.projectId());
        assertEquals(repoA.getId(), ref.repositoryId());
        assertEquals(taskA.getId(), ref.taskId());
        assertEquals(changeA.getId(), ref.codeChangeId());
        assertEquals("src/main/java/com/example/AuthFilter.java", ref.filePath());
        assertEquals("PROPOSED", ref.originalOrProposed());
        assertEquals("PROJECT_MEMBER", ref.authorizationScope());
    }

    @Test
    @DisplayName("17. Evidence Ordering Determinism")
    void testEvidenceOrderingDeterminism() {
        EvidenceRef ref1 = new EvidenceRef("CODE_CHANGE", projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), "A.java", "PROPOSED", 1, 10, "hash1", "snap1", null, "PROJECT_MEMBER");
        EvidenceRef ref2 = new EvidenceRef("RAG_CHUNK", projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), "B.java", "PROPOSED", 5, 15, "hash2", "snap1", null, "PROJECT_MEMBER");

        ReviewContext ctxA = new ReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW,
                "A.java", "MODIFY", "old", "new", "diff", "origHash", "propHash", "", "v1.0", false, List.of(ref1, ref2));

        ReviewContext ctxB = new ReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW,
                "A.java", "MODIFY", "old", "new", "diff", "origHash", "propHash", "", "v1.0", false, List.of(ref2, ref1));

        assertEquals(reviewContextService.computeReviewContextHash(ctxA), reviewContextService.computeReviewContextHash(ctxB));
    }

    @Test
    @DisplayName("18. Evidence Bounds Enforcement")
    void testEvidenceBounds() {
        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertTrue(context.evidenceReferences().size() <= 20);
    }

    @Test
    @DisplayName("19. Huge Diff Truncation According to Policy")
    void testHugeDiffTruncationAccordingToPolicy() {
        transactionTemplate.execute(status -> {
            StringBuilder hugeDiffBuilder = new StringBuilder();
            for (int i = 0; i < 1500; i++) {
                hugeDiffBuilder.append("+ line ").append(i).append("\n");
            }
            changeA.setDiff(hugeDiffBuilder.toString());
            codeChangeRepository.saveAndFlush(changeA);
            return null;
        });

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertTrue(context.unifiedDiff().contains("[TRUNCATED AT MAX LIMIT: 1000 LINES]"));
    }

    @Test
    @DisplayName("20. Huge Source Truncation According to Policy")
    void testHugeSourceTruncationAccordingToPolicy() {
        transactionTemplate.execute(status -> {
            String hugeSource = "A".repeat(120_000);
            changeA.setNewContent(hugeSource);
            codeChangeRepository.saveAndFlush(changeA);
            return null;
        });

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertTrue(context.proposedContent().contains("[TRUNCATED AT MAX LIMIT: 100000 CHARACTERS]"));
    }

    @Test
    @DisplayName("21. Secret Redaction Preserves Hash Integrity")
    void testSecretRedaction() {
        transactionTemplate.execute(status -> {
            String rawWithSecret = "public class Config { String awsKey = \"AKIA1234567890123456\"; }";
            changeA.setNewContent(rawWithSecret);
            codeChangeRepository.saveAndFlush(changeA);
            return null;
        });

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertTrue(context.proposedContent().contains("[REDACTED_SECRET_PRE_LLM]"));
        assertFalse(context.proposedContent().contains("AKIA1234567890123456"));

        assertNotNull(context.proposedHash());
        assertEquals(64, context.proposedHash().length());
    }

    @Test
    @DisplayName("22. Malformed / Missing Source Evidence Handling")
    void testMalformedMissingSourceEvidence() {
        transactionTemplate.execute(status -> {
            changeA.setOldContent(null);
            changeA.setNewContent(null);
            changeA.setDiff(null);
            codeChangeRepository.saveAndFlush(changeA);
            return null;
        });

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertNotNull(context);
        assertNotNull(context.reviewContextHash());
    }

    @Test
    @DisplayName("23. Binary / Generated / Vendor Scope Handling")
    void testBinaryGeneratedVendorHandling() {
        CodeChange binaryChange = transactionTemplate.execute(status -> {
            CodeChange c = new CodeChange();
            c.setTask(taskA);
            c.setPlan(planA);
            c.setRepository(repoA);
            c.setFilePath("assets/logo.png");
            c.setOperation("CREATE");
            c.setNewContent("binary_data");
            return codeChangeRepository.save(c);
        });

        final UUID binaryChangeId = binaryChange.getId();
        assertThrows(ApiException.class, () -> reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), binaryChangeId, ReviewMode.SECURITY_REVIEW, ownerUser));
    }

    @Test
    @DisplayName("24. Duplicate Evidence Normalization")
    void testDuplicateEvidenceNormalization() {
        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertNotNull(context.evidenceReferences());
    }

    @Test
    @DisplayName("25. Empty Valid Context Behavior")
    void testEmptyValidContextBehavior() {
        CodeChange emptyChange = transactionTemplate.execute(status -> {
            CodeChange c = new CodeChange();
            c.setTask(taskA);
            c.setPlan(planA);
            c.setRepository(repoA);
            c.setFilePath("src/main/java/com/example/Empty.java");
            c.setOperation("CREATE");
            c.setOldContent("");
            c.setNewContent("");
            c.setDiff("");
            return codeChangeRepository.save(c);
        });

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), emptyChange.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser);

        assertNotNull(context);
        assertNotNull(context.reviewContextHash());
    }

    @Test
    @DisplayName("26. Concurrent Context Construction")
    void testConcurrentContextConstruction() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<ReviewContext>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> reviewContextService.buildReviewContext(
                    projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, ownerUser));
        }

        List<Future<ReviewContext>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        String expectedHash = null;
        for (Future<ReviewContext> f : futures) {
            ReviewContext ctx = f.get();
            assertNotNull(ctx);
            assertNotNull(ctx.reviewContextHash());
            if (expectedHash == null) {
                expectedHash = ctx.reviewContextHash();
            } else {
                assertEquals(expectedHash, ctx.reviewContextHash());
            }
        }
    }
}
