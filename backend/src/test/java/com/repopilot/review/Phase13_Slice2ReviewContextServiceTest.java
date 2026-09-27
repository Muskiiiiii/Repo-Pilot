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
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.review.service.ReviewContextService;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class Phase13_Slice2ReviewContextServiceTest {

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

    private User owner;
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
        owner = new User();
        owner.setEmail("slice2_owner_" + UUID.randomUUID() + "@repopilot.ai");
        owner.setPasswordHash("hash123");
        owner.setName("Slice2 Owner");
        owner.setRole(UserRole.USER);
        owner = userRepository.save(owner);

        projectA = new Project();
        projectA.setName("Slice2 Project A " + UUID.randomUUID());
        projectA.setDescription("Project A");
        projectA.setOwner(owner);
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectB = new Project();
        projectB.setName("Slice2 Project B " + UUID.randomUUID());
        projectB.setDescription("Project B");
        projectB.setOwner(owner);
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        projectMemberRepository.save(new ProjectMember(projectA, owner, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(projectB, owner, ProjectRole.OWNER));

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
        taskA.setCreatedBy(owner);
        taskA.setTitle("Task A");
        taskA.setDescription("Description A");
        taskA.setStatus(TaskStatus.ANALYZING);
        taskA.setPriority(TaskPriority.HIGH);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(owner);
        taskB.setTitle("Task B");
        taskB.setDescription("Description B");
        taskB.setStatus(TaskStatus.ANALYZING);
        taskB.setPriority(TaskPriority.HIGH);
        taskB = taskRepository.save(taskB);

        planA = new Plan();
        planA.setTask(taskA);
        planA.setObjective("Plan A Objective");
        planA = planRepository.save(planA);

        changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setPlan(planA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/Main.java");
        changeA.setOperation("MODIFY");
        changeA.setOldContent("public class Main {}");
        changeA.setNewContent("public class Main { void run() {} }");
        changeA.setOriginalHash("orig_sha256_base");
        changeA.setProposedHash("prop_sha256_commit");
        changeA.setDiff("--- src/Main.java\n+++ src/Main.java\n@@ -1 +1 @@\n-public class Main {}\n+public class Main { void run() {} }\n");
        changeA = codeChangeRepository.save(changeA);
    }

    @Test
    @DisplayName("Authoritative Derivation: ReviewContext derives ALL fields from persisted DB state")
    void testAuthoritativeContextDerivationFromDB() {
        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner);

        assertNotNull(context);
        assertEquals(projectA.getId(), context.projectId());
        assertEquals(repoA.getId(), context.repositoryId());
        assertEquals(taskA.getId(), context.taskId());
        assertEquals(changeA.getId(), context.codeChangeId());
        assertEquals("src/Main.java", context.filePath());
        assertEquals("orig_sha256_base", context.originalHash());
        assertEquals("prop_sha256_commit", context.proposedHash());
        assertTrue(context.unifiedDiff().contains("public class Main { void run() {} }"));
        assertNotNull(context.reviewContextHash());
    }

    @Test
    @DisplayName("Identity: Attacker combining valid codeChangeId with mismatched project is REJECTED")
    void testCrossProjectCodeChangeRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewContextService.buildReviewContext(projectB.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("Identity: Attacker combining valid codeChangeId with mismatched repo is REJECTED")
    void testCrossRepositoryCodeChangeRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewContextService.buildReviewContext(projectA.getId(), repoB.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("Identity: Attacker combining valid codeChangeId with mismatched task is REJECTED")
    void testCrossTaskCodeChangeRejected() {
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewContextService.buildReviewContext(projectA.getId(), repoA.getId(), taskB.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName("Identity: Nonexistent CodeChange is REJECTED with NOT_FOUND")
    void testNonexistentCodeChangeRejected() {
        UUID nonexistentId = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewContextService.buildReviewContext(projectA.getId(), repoA.getId(), taskA.getId(), nonexistentId, ReviewMode.CODE_CHANGE_REVIEW, owner));
        assertEquals("CODE_CHANGE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("Context Limits Boundary: 511,999 bytes diff -> ACCEPTED")
    void testExactByteBoundary511999Accepted() {
        CodeChange change = new CodeChange();
        change.setTask(taskA);
        change.setPlan(planA);
        change.setRepository(repoA);
        change.setFilePath("src/Exact1.java");
        change.setOperation("MODIFY");

        // Construct diff of exactly 511,999 bytes using 200-byte lines to stay under 10,000 lines limit
        StringBuilder sb = new StringBuilder("--- src/Exact1.java\n+++ src/Exact1.java\n");
        String chunk = "+//" + "A".repeat(180) + "\n";
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length + chunk.getBytes(StandardCharsets.UTF_8).length <= 511_999) {
            sb.append(chunk);
        }
        int rem1 = 511_999 - sb.toString().getBytes(StandardCharsets.UTF_8).length;
        if (rem1 > 0) {
            sb.append("+//").append("B".repeat(Math.max(0, rem1 - 4))).append("\n");
        }
        String fullStr = sb.toString();
        byte[] fullBytes = fullStr.getBytes(StandardCharsets.UTF_8);
        if (fullBytes.length > 511_999) {
            fullStr = new String(fullBytes, 0, 511_999, StandardCharsets.UTF_8);
        }
        change.setDiff(fullStr);
        change = codeChangeRepository.save(change);

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), change.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner);
        assertNotNull(context);
    }

    @Test
    @DisplayName("Context Limits Boundary: 512,000 bytes diff -> ACCEPTED")
    void testExactByteBoundary512000Accepted() {
        CodeChange change = new CodeChange();
        change.setTask(taskA);
        change.setPlan(planA);
        change.setRepository(repoA);
        change.setFilePath("src/Exact2.java");
        change.setOperation("MODIFY");

        // Construct diff of exactly 512,000 bytes using 200-byte lines
        StringBuilder sb = new StringBuilder("--- src/Exact2.java\n+++ src/Exact2.java\n");
        String chunk = "+//" + "A".repeat(180) + "\n";
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length + chunk.getBytes(StandardCharsets.UTF_8).length <= 512_000) {
            sb.append(chunk);
        }
        int rem2 = 512_000 - sb.toString().getBytes(StandardCharsets.UTF_8).length;
        if (rem2 > 0) {
            sb.append("+//").append("B".repeat(Math.max(0, rem2 - 4))).append("\n");
        }
        String fullStr = sb.toString();
        byte[] fullBytes = fullStr.getBytes(StandardCharsets.UTF_8);
        if (fullBytes.length > 512_000) {
            fullStr = new String(fullBytes, 0, 512_000, StandardCharsets.UTF_8);
        }
        change.setDiff(fullStr);
        change = codeChangeRepository.save(change);

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), change.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner);
        assertNotNull(context);
    }

    @Test
    @DisplayName("Context Limits Boundary: 512,001 bytes diff -> REJECTED_CONTEXT_TOO_LARGE")
    void testExactByteBoundary512001Rejected() {
        CodeChange change = new CodeChange();
        change.setTask(taskA);
        change.setPlan(planA);
        change.setRepository(repoA);
        change.setFilePath("src/Exact3.java");
        change.setOperation("MODIFY");

        // Construct diff of 512,001 bytes using 200-byte lines
        StringBuilder sb = new StringBuilder("--- src/Exact3.java\n+++ src/Exact3.java\n");
        String chunk = "+// UTF-8 symbol € " + "A".repeat(170) + "\n";
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length < 512_001) {
            sb.append(chunk);
        }
        change.setDiff(sb.toString());
        change = codeChangeRepository.save(change);

        final UUID id = change.getId();
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewContextService.buildReviewContext(projectA.getId(), repoA.getId(), taskA.getId(), id, ReviewMode.CODE_CHANGE_REVIEW, owner));

        assertEquals("REJECTED_CONTEXT_TOO_LARGE", ex.getCode());
        assertTrue(ex.getMessage().contains("exceeds maximum limit"), ex.getMessage());
    }

    @Test
    @DisplayName("Context Limits Boundary: 50 changed files -> ACCEPTED")
    void testExactFileBoundary50Accepted() {
        Task task50 = new Task();
        task50.setProject(projectA);
        task50.setRepository(repoA);
        task50.setCreatedBy(owner);
        task50.setTitle("Task 50 Files");
        task50.setStatus(TaskStatus.ANALYZING);
        task50 = taskRepository.save(task50);

        for (int i = 0; i < 50; i++) {
            CodeChange cc = new CodeChange();
            cc.setTask(task50);
            cc.setRepository(repoA);
            cc.setFilePath("src/File" + i + ".java");
            cc.setOperation("CREATE");
            cc.setDiff("--- src/File" + i + ".java\n+++ src/File" + i + ".java\n+class File" + i + " {}\n");
            codeChangeRepository.save(cc);
        }

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), task50.getId(), null, ReviewMode.TASK_IMPLEMENTATION_REVIEW, owner);
        assertNotNull(context);
    }

    @Test
    @DisplayName("Context Limits Boundary: 51 changed files -> REJECTED_CONTEXT_TOO_LARGE")
    void testExactFileBoundary51Rejected() {
        Task task51 = new Task();
        task51.setProject(projectA);
        task51.setRepository(repoA);
        task51.setCreatedBy(owner);
        task51.setTitle("Task 51 Files");
        task51.setStatus(TaskStatus.ANALYZING);
        task51 = taskRepository.save(task51);

        for (int i = 0; i < 51; i++) {
            CodeChange cc = new CodeChange();
            cc.setTask(task51);
            cc.setRepository(repoA);
            cc.setFilePath("src/File" + i + ".java");
            cc.setOperation("CREATE");
            cc.setDiff("--- src/File" + i + ".java\n+++ src/File" + i + ".java\n+class File" + i + " {}\n");
            codeChangeRepository.save(cc);
        }

        final UUID tId = task51.getId();
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewContextService.buildReviewContext(projectA.getId(), repoA.getId(), tId, null, ReviewMode.TASK_IMPLEMENTATION_REVIEW, owner));

        assertEquals("REJECTED_CONTEXT_TOO_LARGE", ex.getCode());
        assertTrue(ex.getMessage().contains("File count"), ex.getMessage());
    }

    @Test
    @DisplayName("Context Limits Boundary: 10,000 changed lines -> ACCEPTED")
    void testExactLineBoundary10000Accepted() {
        CodeChange change = new CodeChange();
        change.setTask(taskA);
        change.setPlan(planA);
        change.setRepository(repoA);
        change.setFilePath("src/Lines10k.java");
        change.setOperation("MODIFY");

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10_000; i++) {
            sb.append("+int line").append(i).append(" = ").append(i).append(";\n");
        }
        change.setDiff(sb.toString());
        change = codeChangeRepository.save(change);

        ReviewContext context = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), change.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner);
        assertNotNull(context);
    }

    @Test
    @DisplayName("Context Limits Boundary: 10,001 changed lines -> REJECTED_CONTEXT_TOO_LARGE")
    void testExactLineBoundary10001Rejected() {
        CodeChange change = new CodeChange();
        change.setTask(taskA);
        change.setPlan(planA);
        change.setRepository(repoA);
        change.setFilePath("src/Lines10k1.java");
        change.setOperation("MODIFY");

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10_001; i++) {
            sb.append("+int line").append(i).append(" = ").append(i).append(";\n");
        }
        change.setDiff(sb.toString());
        change = codeChangeRepository.save(change);

        final UUID id = change.getId();
        ApiException ex = assertThrows(ApiException.class, () ->
                reviewContextService.buildReviewContext(projectA.getId(), repoA.getId(), taskA.getId(), id, ReviewMode.CODE_CHANGE_REVIEW, owner));

        assertEquals("REJECTED_CONTEXT_TOO_LARGE", ex.getCode());
        assertTrue(ex.getMessage().contains("Changed lines count"), ex.getMessage());
    }

    @Test
    @DisplayName("Source Context Hash: Hash sensitivity mutation tests on every material field")
    void testSourceContextHashSensitivityMutations() {
        ReviewContext baseContext = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner);
        String baseHash = reviewContextService.computeReviewContextHash(baseContext);
        assertNotNull(baseHash);

        // 1. identical context -> identical hash
        ReviewContext identicalContext = reviewContextService.buildReviewContext(
                projectA.getId(), repoA.getId(), taskA.getId(), changeA.getId(), ReviewMode.CODE_CHANGE_REVIEW, owner);
        assertEquals(baseHash, reviewContextService.computeReviewContextHash(identicalContext));

        // 2. baseSha (originalHash) changed -> hash changes
        ReviewContext mutBaseSha = new ReviewContext(
                baseContext.projectId(), baseContext.repositoryId(), baseContext.taskId(), baseContext.codeChangeId(),
                baseContext.reviewMode(), baseContext.filePath(), baseContext.operation(), baseContext.originalContent(),
                baseContext.proposedContent(), baseContext.unifiedDiff(), "mutated_base_sha", baseContext.proposedHash(),
                "", baseContext.contextVersion(), false, baseContext.evidenceReferences()
        );
        assertNotEquals(baseHash, reviewContextService.computeReviewContextHash(mutBaseSha));

        // 3. commitSha (proposedHash) changed -> hash changes
        ReviewContext mutCommitSha = new ReviewContext(
                baseContext.projectId(), baseContext.repositoryId(), baseContext.taskId(), baseContext.codeChangeId(),
                baseContext.reviewMode(), baseContext.filePath(), baseContext.operation(), baseContext.originalContent(),
                baseContext.proposedContent(), baseContext.unifiedDiff(), baseContext.originalHash(), "mutated_commit_sha",
                "", baseContext.contextVersion(), false, baseContext.evidenceReferences()
        );
        assertNotEquals(baseHash, reviewContextService.computeReviewContextHash(mutCommitSha));

        // 4. diffHash (unifiedDiff) changed -> hash changes
        ReviewContext mutDiff = new ReviewContext(
                baseContext.projectId(), baseContext.repositoryId(), baseContext.taskId(), baseContext.codeChangeId(),
                baseContext.reviewMode(), baseContext.filePath(), baseContext.operation(), baseContext.originalContent(),
                baseContext.proposedContent(), "--- mutated diff\n+++ mutated diff\n", baseContext.originalHash(), baseContext.proposedHash(),
                "", baseContext.contextVersion(), false, baseContext.evidenceReferences()
        );
        assertNotEquals(baseHash, reviewContextService.computeReviewContextHash(mutDiff));

        // 5. file list (filePath) changed -> hash changes
        ReviewContext mutFile = new ReviewContext(
                baseContext.projectId(), baseContext.repositoryId(), baseContext.taskId(), baseContext.codeChangeId(),
                baseContext.reviewMode(), "src/OtherFile.java", baseContext.operation(), baseContext.originalContent(),
                baseContext.proposedContent(), baseContext.unifiedDiff(), baseContext.originalHash(), baseContext.proposedHash(),
                "", baseContext.contextVersion(), false, baseContext.evidenceReferences()
        );
        assertNotEquals(baseHash, reviewContextService.computeReviewContextHash(mutFile));

        // 6. repository/project/task identity changed -> hash changes
        ReviewContext mutProj = new ReviewContext(
                UUID.randomUUID(), baseContext.repositoryId(), baseContext.taskId(), baseContext.codeChangeId(),
                baseContext.reviewMode(), baseContext.filePath(), baseContext.operation(), baseContext.originalContent(),
                baseContext.proposedContent(), baseContext.unifiedDiff(), baseContext.originalHash(), baseContext.proposedHash(),
                "", baseContext.contextVersion(), false, baseContext.evidenceReferences()
        );
        assertNotEquals(baseHash, reviewContextService.computeReviewContextHash(mutProj));
    }
}
