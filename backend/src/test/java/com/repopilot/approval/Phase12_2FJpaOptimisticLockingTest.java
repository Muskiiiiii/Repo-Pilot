package com.repopilot.approval;

import com.repopilot.approval.dto.PullRequestPublicationStatus;
import com.repopilot.approval.entity.ApprovalStatus;
import com.repopilot.approval.entity.HumanApproval;
import com.repopilot.approval.entity.PullRequestPublication;
import com.repopilot.approval.repository.HumanApprovalRepository;
import com.repopilot.approval.repository.PullRequestPublicationRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.boot.test.mock.mockito.MockBean;
import com.repopilot.approval.service.*;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase12_2FJpaOptimisticLockingTest {

    @MockBean
    private ApprovedChangeVerifier approvedChangeVerifier;

    @MockBean
    private MutationIntegrityVerifier mutationIntegrityVerifier;

    @MockBean
    private GitBranchCommitBoundary gitBranchCommitBoundary;

    @MockBean
    private GithubRemoteIdentityVerifier githubRemoteIdentityVerifier;

    @MockBean
    private GitRemotePushBoundary gitRemotePushBoundary;

    @MockBean
    private ApprovedPatchMaterializer approvedPatchMaterializer;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private CodeReviewRepository codeReviewRepository;

    @Autowired
    private HumanApprovalRepository humanApprovalRepository;

    @Autowired
    private PullRequestPublicationRepository pullRequestPublicationRepository;

    private UUID publicationId;
    private UUID approvalId;

    @BeforeEach
    void setUp() {
        pullRequestPublicationRepository.deleteAll();

        User user = new User();
        user.setName("Worker User");
        user.setPasswordHash("hashed_password_123");
        user.setRole(com.repopilot.user.entity.UserRole.USER);
        user.setEmail("worker-test-" + UUID.randomUUID() + "@example.com");
        user.setGithubUsername("worker-user");
        user.setGithubAccessToken("ENCRYPTED_TOKEN");
        user = userRepository.save(user);

        Project project = new Project();
        project.setName("Test Project " + UUID.randomUUID());
        project.setOwner(user);
        project = projectRepository.save(project);

        Repository repo = new Repository();
        repo.setProject(project);
        repo.setOwnerName("test-owner");
        repo.setRepoName("test-repo");
        repo.setGithubRepoId("12345678");
        repo.setDefaultBranch("main");
        repo = repositoryRepository.save(repo);

        Task task = new Task();
        task.setProject(project);
        task.setTitle("Test Task");
        task.setCreatedBy(user);
        task = taskRepository.save(task);

        CodeChange change = new CodeChange();
        change.setTask(task);
        change.setFilePath("src/Main.java");
        change.setOperation("MODIFY");
        change = codeChangeRepository.save(change);

        CodeReview review = new CodeReview();
        review.setCodeChange(change);
        review.setProject(project);
        review.setRepository(repo);
        review.setTask(task);
        review.setReviewContextHash("context_hash_123");
        review.setModelIdentifier("test-model");
        review = codeReviewRepository.save(review);

        HumanApproval approval = new HumanApproval();
        approval.setProject(project);
        approval.setRepository(repo);
        approval.setTask(task);
        approval.setCodeChange(change);
        approval.setReview(review);
        approval.setApprovedByUser(user);
        approval.setApprovedByUserEmail(user.getEmail());
        approval.setApprovedByUserRole("MEMBER");
        approval.setRepositoryBaseSha("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b");
        approval.setCodeChangeOriginalHash("orig_hash_123");
        approval.setCodeChangeProposedHash("prop_hash_123");
        approval.setCodeChangeDiffHash("diff_hash_123");
        approval.setReviewContextHash("context_hash_123");
        approval.setApprovedFiles("[\"src/Main.java\"]");
        approval.setApprovedChangeCount(1);
        approval.setApprovalRecordHash("record_hash_123");
        approval.setIdempotencyKey(UUID.randomUUID().toString());
        approval.setStatus(ApprovalStatus.APPROVED);
        approval = humanApprovalRepository.save(approval);
        approvalId = approval.getId();

        PullRequestPublication pub = new PullRequestPublication();
        pub.setApproval(approval);
        pub.setCodeChange(change);
        pub.setRepository(repo);
        pub.setTask(task);
        pub.setProject(project);
        pub.setInitiatedByUser(user);
        pub.setBranchName("repopilot/task-12345678-87654321");
        pub.setBaseBranch("main");
        pub.setCommitSha("9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e");
        pub.setParentCommitSha("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b");
        pub.setTreeSha("7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b");
        pub.setGithubRepoId("12345678");
        pub.setPublicationStatus(PullRequestPublicationStatus.PENDING);
        pub = pullRequestPublicationRepository.save(pub);

        publicationId = pub.getId();
    }

    @Test
    @DisplayName("1. Real JPA @Version Collision: Worker A stale write fails closed without corrupting Worker B's state")
    void testRealJpaOptimisticLocking_StaleWriterCollision() {
        EntityManager emA = entityManagerFactory.createEntityManager();
        EntityManager emB = entityManagerFactory.createEntityManager();

        try {
            // Worker A loads publication at version N (0)
            emA.getTransaction().begin();
            PullRequestPublication pubA = emA.find(PullRequestPublication.class, publicationId);
            Long versionA = pubA.getVersion();
            emA.getTransaction().commit();

            // Worker B loads the exact same publication at version N (0) and updates to RECONCILED (version becomes N+1)
            emB.getTransaction().begin();
            PullRequestPublication pubB = emB.find(PullRequestPublication.class, publicationId);
            assertEquals(versionA, pubB.getVersion());
            pubB.setPublicationStatus(PullRequestPublicationStatus.RECONCILED);
            emB.getTransaction().commit(); // Commits version 1

            // Worker A attempts to save its stale entity pubA (containing version 0)
            emA.getTransaction().begin();
            pubA.setPublicationStatus(PullRequestPublicationStatus.FAILED_PUSH);
            pubA.setFailureCategory("UNKNOWN_PUSH_STATE");

            assertThrows(Exception.class, () -> {
                emA.merge(pubA);
                emA.getTransaction().commit();
            });

            if (emA.getTransaction().isActive()) {
                emA.getTransaction().rollback();
            }

            // Verify in fresh transaction: Worker B's RECONCILED state remains intact at version 1
            EntityManager emCheck = entityManagerFactory.createEntityManager();
            PullRequestPublication freshPub = emCheck.find(PullRequestPublication.class, publicationId);
            assertEquals(PullRequestPublicationStatus.RECONCILED, freshPub.getPublicationStatus());
            assertEquals(versionA + 1, freshPub.getVersion());
            assertNull(freshPub.getFailureCategory());
            emCheck.close();

        } finally {
            emA.close();
            emB.close();
        }
    }

    @Test
    @DisplayName("2. Scenario A: Worker A stale PUSHING entity cannot overwrite Worker B's RECONCILED state")
    void testStalePushing_WorkerBReconciles_WorkerAFails() {
        EntityManager emA = entityManagerFactory.createEntityManager();
        EntityManager emB = entityManagerFactory.createEntityManager();

        try {
            emA.getTransaction().begin();
            PullRequestPublication pubA = emA.find(PullRequestPublication.class, publicationId);
            pubA.setPublicationStatus(PullRequestPublicationStatus.VERIFYING);
            emA.getTransaction().commit();

            // Worker A reads state at VERIFYING
            emA.getTransaction().begin();
            PullRequestPublication pubA2 = emA.find(PullRequestPublication.class, publicationId);
            emA.getTransaction().commit();

            // Worker B updates state to RECONCILED
            emB.getTransaction().begin();
            PullRequestPublication pubB = emB.find(PullRequestPublication.class, publicationId);
            pubB.setPublicationStatus(PullRequestPublicationStatus.RECONCILED);
            emB.getTransaction().commit();

            // Worker A attempts to transition stale entity to FAILED_PUSH
            emA.getTransaction().begin();
            pubA2.setPublicationStatus(PullRequestPublicationStatus.FAILED_PUSH);
            final PullRequestPublication staleToMerge = pubA2;
            assertThrows(Exception.class, () -> {
                emA.merge(staleToMerge);
                emA.getTransaction().commit();
            });

            if (emA.getTransaction().isActive()) {
                emA.getTransaction().rollback();
            }

            // Verify DB state is still RECONCILED
            EntityManager emCheck = entityManagerFactory.createEntityManager();
            PullRequestPublication freshPub = emCheck.find(PullRequestPublication.class, publicationId);
            assertEquals(PullRequestPublicationStatus.RECONCILED, freshPub.getPublicationStatus());
            emCheck.close();

        } finally {
            emA.close();
            emB.close();
        }
    }

    @Test
    @DisplayName("3. Scenario B: Worker A stale VERIFYING entity cannot transition to PUSHING when Worker B has reconciled")
    void testStaleVerifying_WorkerBReconciles_WorkerAPushes() {
        EntityManager emA = entityManagerFactory.createEntityManager();
        EntityManager emB = entityManagerFactory.createEntityManager();

        try {
            emA.getTransaction().begin();
            PullRequestPublication pubA = emA.find(PullRequestPublication.class, publicationId);
            emA.getTransaction().commit();

            // Worker B reconciles
            emB.getTransaction().begin();
            PullRequestPublication pubB = emB.find(PullRequestPublication.class, publicationId);
            pubB.setPublicationStatus(PullRequestPublicationStatus.RECONCILED);
            emB.getTransaction().commit();

            // Worker A attempts to set PUSHING on stale entity
            emA.getTransaction().begin();
            pubA.setPublicationStatus(PullRequestPublicationStatus.PUSHING);
            assertThrows(Exception.class, () -> {
                emA.merge(pubA);
                emA.getTransaction().commit();
            });

            if (emA.getTransaction().isActive()) {
                emA.getTransaction().rollback();
            }

            EntityManager emCheck = entityManagerFactory.createEntityManager();
            PullRequestPublication freshPub = emCheck.find(PullRequestPublication.class, publicationId);
            assertEquals(PullRequestPublicationStatus.RECONCILED, freshPub.getPublicationStatus());
            emCheck.close();

        } finally {
            emA.close();
            emB.close();
        }
    }

    @Test
    @DisplayName("4. Scenario C: Worker A stale non-terminal entity cannot regress terminal status PUSHED")
    void testStaleNonTerminal_WorkerBTerminal_WorkerAAttemptsRegression() {
        EntityManager emA = entityManagerFactory.createEntityManager();
        EntityManager emB = entityManagerFactory.createEntityManager();

        try {
            emA.getTransaction().begin();
            PullRequestPublication pubA = emA.find(PullRequestPublication.class, publicationId);
            emA.getTransaction().commit();

            // Worker B updates to PUSHED
            emB.getTransaction().begin();
            PullRequestPublication pubB = emB.find(PullRequestPublication.class, publicationId);
            pubB.setPublicationStatus(PullRequestPublicationStatus.PUSHED);
            emB.getTransaction().commit();

            // Worker A attempts to update stale entity to PUSHING
            emA.getTransaction().begin();
            pubA.setPublicationStatus(PullRequestPublicationStatus.PUSHING);
            assertThrows(Exception.class, () -> {
                emA.merge(pubA);
                emA.getTransaction().commit();
            });

            if (emA.getTransaction().isActive()) {
                emA.getTransaction().rollback();
            }

            EntityManager emCheck = entityManagerFactory.createEntityManager();
            PullRequestPublication freshPub = emCheck.find(PullRequestPublication.class, publicationId);
            assertEquals(PullRequestPublicationStatus.PUSHED, freshPub.getPublicationStatus());
            emCheck.close();

        } finally {
            emA.close();
            emB.close();
        }
    }

    @Test
    @DisplayName("5. Defense-in-Depth: @Version optimistic locking collision succeeds independently of Redis fencing")
    void testVersionCollisionIndependentOfRedisFencing() {
        // Even if Redis fencing is completely bypassed, JPA @Version prevents database collision
        EntityManager emA = entityManagerFactory.createEntityManager();
        EntityManager emB = entityManagerFactory.createEntityManager();

        try {
            emA.getTransaction().begin();
            PullRequestPublication pubA = emA.find(PullRequestPublication.class, publicationId);
            emA.getTransaction().commit();

            emB.getTransaction().begin();
            PullRequestPublication pubB = emB.find(PullRequestPublication.class, publicationId);
            pubB.setPublicationStatus(PullRequestPublicationStatus.PUSHED);
            emB.getTransaction().commit();

            emA.getTransaction().begin();
            pubA.setPublicationStatus(PullRequestPublicationStatus.FAILED_PUSH);
            assertThrows(Exception.class, () -> {
                emA.merge(pubA);
                emA.getTransaction().commit();
            });

            if (emA.getTransaction().isActive()) {
                emA.getTransaction().rollback();
            }

        } finally {
            emA.close();
            emB.close();
        }
    }
}
