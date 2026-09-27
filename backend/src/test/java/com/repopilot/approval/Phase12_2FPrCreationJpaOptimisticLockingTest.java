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
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.repopilot.approval.service.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase12_2FPrCreationJpaOptimisticLockingTest {

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
    private GitPullRequestCreationBoundary gitPullRequestCreationBoundary;

    @MockBean
    private ApprovedPatchMaterializer approvedPatchMaterializer;

    @Autowired
    private PullRequestPublicationRepository pullRequestPublicationRepository;

    @Autowired
    private HumanApprovalRepository humanApprovalRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private com.repopilot.review.repository.CodeReviewRepository codeReviewRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private PullRequestPublication publication;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("user-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("hashedpassword");
        user.setGithubUsername("testuser");
        user.setName("Test User");
        user = userRepository.save(user);

        Project project = new Project();
        project.setName("Test Project");
        project.setOwner(user);
        project = projectRepository.save(project);

        Repository repo = new Repository();
        repo.setProject(project);
        repo.setOwnerName("owner");
        repo.setRepoName("repo");
        repo.setGithubRepoId("12345");
        repo.setDefaultBranch("main");
        repo = repositoryRepository.save(repo);

        Task task = new Task();
        task.setProject(project);
        task.setTitle("Test Task");
        task.setCreatedBy(user);
        task = taskRepository.save(task);

        CodeChange codeChange = new CodeChange();
        codeChange.setTask(task);
        codeChange.setFilePath("src/Main.java");
        codeChange.setOperation("MODIFY");
        codeChange = codeChangeRepository.save(codeChange);

        com.repopilot.review.entity.CodeReview review = new com.repopilot.review.entity.CodeReview();
        review.setCodeChange(codeChange);
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
        approval.setCodeChange(codeChange);
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

        publication = new PullRequestPublication();
        publication.setApproval(approval);
        publication.setCodeChange(codeChange);
        publication.setRepository(repo);
        publication.setTask(task);
        publication.setProject(project);
        publication.setInitiatedByUser(user);
        publication.setBranchName("repopilot/task-test");
        publication.setBaseBranch("main");
        publication.setCommitSha("9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e");
        publication.setParentCommitSha("0000000000000000000000000000000000000000");
        publication.setTreeSha("1111111111111111111111111111111111111111");
        publication.setGithubRepoId("12345");
        publication.setPublicationStatus(PullRequestPublicationStatus.PUSHED);

        publication = pullRequestPublicationRepository.saveAndFlush(publication);
    }

    @Test
    @DisplayName("1. Optimistic Locking: Concurrent writer A throws ObjectOptimisticLockingFailureException if writer B updates version N")
    void testOptimisticLocking_ConcurrentWritersThrowOptimisticLockingFailure() {
        PullRequestPublication writerA = pullRequestPublicationRepository.findById(publication.getId()).orElseThrow();
        PullRequestPublication writerB = pullRequestPublicationRepository.findById(publication.getId()).orElseThrow();

        writerB.setPublicationStatus(PullRequestPublicationStatus.PR_CREATED);
        writerB.setGithubPrNumber(42L);
        writerB.setPrUrl("https://github.com/owner/repo/pull/42");
        pullRequestPublicationRepository.saveAndFlush(writerB);

        writerA.setPublicationStatus(PullRequestPublicationStatus.FAILED_PR_CREATION);
        writerA.setFailureCategory("STALE_WRITE");

        assertThrows(ObjectOptimisticLockingFailureException.class, () -> {
            pullRequestPublicationRepository.saveAndFlush(writerA);
        });

        entityManager.clear();
        PullRequestPublication persisted = pullRequestPublicationRepository.findById(publication.getId()).orElseThrow();
        assertEquals(PullRequestPublicationStatus.PR_CREATED, persisted.getPublicationStatus());
        assertEquals(42L, persisted.getGithubPrNumber());
    }

    @Test
    @DisplayName("2. Version Tracking: Version increments monotonically on successful PR creation updates")
    void testVersionIncrement_OnSuccessfulPrCreationUpdate() {
        Long initialVersion = publication.getVersion();

        publication.setPublicationStatus(PullRequestPublicationStatus.PR_CREATING);
        publication = pullRequestPublicationRepository.saveAndFlush(publication);
        assertEquals(initialVersion + 1, publication.getVersion());

        publication.setPublicationStatus(PullRequestPublicationStatus.PR_CREATED);
        publication.setGithubPrNumber(100L);
        publication.setPrUrl("https://github.com/owner/repo/pull/100");
        publication = pullRequestPublicationRepository.saveAndFlush(publication);
        assertEquals(initialVersion + 2, publication.getVersion());
    }
}
