package com.repopilot.repository;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.plan.entity.Plan;
import com.repopilot.plan.repository.PlanRepository;
import com.repopilot.project.entity.*;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.pullrequest.entity.PullRequest;
import com.repopilot.pullrequest.repository.PullRequestRepository;
import com.repopilot.repository.entity.*;
import com.repopilot.repository.repository.*;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.task.entity.*;
import com.repopilot.task.repository.AgentRunRepository;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.entity.TestResult;
import com.repopilot.testing.entity.TestRun;
import com.repopilot.testing.repository.TestResultRepository;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SchemaIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryBranchRepository repositoryBranchRepository;

    @Autowired
    private RepositoryFileRepository repositoryFileRepository;

    @Autowired
    private CodeChunkRepository codeChunkRepository;

    @Autowired
    private EmbeddingRepository embeddingRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private TestResultRepository testResultRepository;

    @Autowired
    private CodeReviewRepository codeReviewRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void testFullDomainEntityPersistence() {
        // 1. Create User
        User user = new User();
        user.setEmail("owner@repopilot.ai");
        user.setPasswordHash("hash123");
        user.setName("Project Owner");
        user.setRole(UserRole.USER);
        User savedUser = userRepository.save(user);
        assertNotNull(savedUser.getId());

        // 2. Create Project
        Project project = new Project();
        project.setName("RepoPilot Core");
        project.setDescription("AI Engine for Code");
        project.setOwner(savedUser);
        project.setStatus(ProjectStatus.ACTIVE);
        Project savedProject = projectRepository.save(project);
        assertNotNull(savedProject.getId());

        // 3. Create Project Member
        ProjectMember member = new ProjectMember(savedProject, savedUser, ProjectRole.OWNER);
        ProjectMember savedMember = projectMemberRepository.save(member);
        assertNotNull(savedMember.getId());

        // 4. Create Repository
        com.repopilot.repository.entity.Repository repo = new com.repopilot.repository.entity.Repository();
        repo.setProject(savedProject);
        repo.setOwnerName("repopilot");
        repo.setRepoName("repopilot-core");
        repo.setDefaultBranch("main");
        repo.setVisibility(RepositoryVisibility.PUBLIC);
        repo.setIndexStatus(RepositoryIndexStatus.INDEXED);
        com.repopilot.repository.entity.Repository savedRepo = repositoryRepository.save(repo);
        assertNotNull(savedRepo.getId());

        // 5. Create Branch
        RepositoryBranch branch = new RepositoryBranch();
        branch.setRepository(savedRepo);
        branch.setName("main");
        branch.setCommitSha("abcdef1234567890");
        branch.setIsDefault(true);
        RepositoryBranch savedBranch = repositoryBranchRepository.save(branch);
        assertNotNull(savedBranch.getId());

        // 6. Create File
        RepositoryFile file = new RepositoryFile();
        file.setRepository(savedRepo);
        file.setBranch(savedBranch);
        file.setPath("src/Main.java");
        file.setLanguage("Java");
        file.setFileSize(1024L);
        RepositoryFile savedFile = repositoryFileRepository.save(file);
        assertNotNull(savedFile.getId());

        // 7. Create Code Chunk
        CodeChunk chunk = new CodeChunk();
        chunk.setFile(savedFile);
        chunk.setChunkIndex(0);
        chunk.setContent("public class Main { public static void main(String[] args) {} }");
        chunk.setStartLine(1);
        chunk.setEndLine(5);
        CodeChunk savedChunk = codeChunkRepository.save(chunk);
        assertNotNull(savedChunk.getId());

        // 8. Create Embedding
        Embedding embedding = new Embedding();
        embedding.setChunk(savedChunk);
        embedding.setModelName("voyage-code-4");
        embedding.setDimensions(1024);
        embedding.setEmbedding("[0.01, 0.02, 0.03]");
        Embedding savedEmbedding = embeddingRepository.save(embedding);
        assertNotNull(savedEmbedding.getId());

        // 9. Create Task
        Task task = new Task();
        task.setProject(savedProject);
        task.setRepository(savedRepo);
        task.setCreatedBy(savedUser);
        task.setTitle("Fix auth vulnerability");
        task.setDescription("Prevent JWT replay");
        task.setStatus(TaskStatus.ANALYZING);
        task.setPriority(TaskPriority.HIGH);
        Task savedTask = taskRepository.save(task);
        assertNotNull(savedTask.getId());

        // 10. Create Task Execution
        TaskExecution execution = new TaskExecution();
        execution.setTask(savedTask);
        execution.setExecutionNumber(1);
        execution.setStatus(ExecutionStatus.RUNNING);
        TaskExecution savedExecution = taskExecutionRepository.save(execution);
        assertNotNull(savedExecution.getId());

        // 11. Create Agent Run
        AgentRun agentRun = new AgentRun();
        agentRun.setTaskExecution(savedExecution);
        agentRun.setAgentType(AgentType.PLANNER);
        agentRun.setStatus(AgentRunStatus.COMPLETED);
        agentRun.setTokensUsed(250L);
        agentRun.setLatencyMs(1200L);
        AgentRun savedAgentRun = agentRunRepository.save(agentRun);
        assertNotNull(savedAgentRun.getId());

        // 12. Create Plan
        Plan plan = new Plan();
        plan.setTask(savedTask);
        plan.setObjective("Upgrade JJWT dependency and validate claims");
        Plan savedPlan = planRepository.save(plan);
        assertNotNull(savedPlan.getId());

        // 13. Create Code Change
        CodeChange change = new CodeChange();
        change.setTask(savedTask);
        change.setPlan(savedPlan);
        change.setFilePath("pom.xml");
        change.setOperation("MODIFY");
        CodeChange savedChange = codeChangeRepository.save(change);
        assertNotNull(savedChange.getId());

        // 14. Create Test Run & Result
        TestRun testRun = new TestRun();
        testRun.setTask(savedTask);
        testRun.setCommand("mvn test");
        testRun.setStatus("PASSED");
        TestRun savedTestRun = testRunRepository.save(testRun);
        assertNotNull(savedTestRun.getId());

        TestResult testResult = new TestResult();
        testResult.setTestRun(savedTestRun);
        testResult.setTestName("JwtTokenProviderTest");
        testResult.setStatus("SUCCESS");
        TestResult savedTestResult = testResultRepository.save(testResult);
        assertNotNull(savedTestResult.getId());

        // 15. Create Code Review & PR
        CodeReview review = new CodeReview();
        review.setProject(savedProject);
        review.setRepository(savedRepo);
        review.setTask(savedTask);
        review.setReviewContextHash("dummyhash123");
        review.setModelIdentifier("gemini-2.5-pro");
        review.setStatus(com.repopilot.review.entity.ReviewStatus.COMPLETED);
        CodeReview savedReview = codeReviewRepository.save(review);
        assertNotNull(savedReview.getId());

        PullRequest pr = new PullRequest();
        pr.setTask(savedTask);
        pr.setRepository(savedRepo);
        pr.setBranchName("fix/auth-jwt");
        pr.setTitle("Fix JWT replay vulnerability");
        PullRequest savedPr = pullRequestRepository.save(pr);
        assertNotNull(savedPr.getId());

        // 16. Create Audit Log
        AuditLog auditLog = new AuditLog();
        auditLog.setUser(savedUser);
        auditLog.setProject(savedProject);
        auditLog.setTask(savedTask);
        auditLog.setAction("TASK_CREATED");
        auditLog.setMetadata("{\"key\":\"value\"}");
        AuditLog savedAuditLog = auditLogRepository.save(auditLog);
        assertNotNull(savedAuditLog.getId());
        assertEquals("{\"key\":\"value\"}", savedAuditLog.getMetadata());
    }
}
