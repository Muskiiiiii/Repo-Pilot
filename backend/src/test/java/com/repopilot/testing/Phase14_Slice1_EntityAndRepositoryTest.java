package com.repopilot.testing;

import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.repository.SandboxExecutionArtifactRepository;
import com.repopilot.testing.repository.SandboxJobExecutionRepository;
import com.repopilot.testing.repository.SandboxWorkerNodeRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase14_Slice1_EntityAndRepositoryTest {

    @Autowired private SandboxWorkerNodeRepository sandboxWorkerNodeRepository;
    @Autowired private SandboxJobExecutionRepository sandboxJobExecutionRepository;
    @Autowired private SandboxExecutionArtifactRepository sandboxExecutionArtifactRepository;

    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskExecutionRepository taskExecutionRepository;

    private User ownerUser;
    private Project project;
    private Repository repository;
    private Task task;
    private TaskExecution taskExecution;

    @BeforeEach
    void setUp() {
        tearDown();

        ownerUser = new User();
        ownerUser.setEmail("slice141_owner_" + UUID.randomUUID() + "@example.com");
        ownerUser.setPasswordHash("hash");
        ownerUser.setName("Slice 14.1 Owner");
        ownerUser.setRole(UserRole.USER);
        ownerUser = userRepository.save(ownerUser);

        project = new Project();
        project.setName("Slice 14.1 Project");
        project.setDescription("Project for Phase 14.1 entity testing");
        project.setOwner(ownerUser);
        project.setStatus(ProjectStatus.ACTIVE);
        project = projectRepository.save(project);

        repository = new Repository();
        repository.setProject(project);
        repository.setOwnerName("test-owner");
        repository.setRepoName("test-repo-" + UUID.randomUUID().toString().substring(0, 8));
        repository.setDefaultBranch("main");
        repository.setVisibility(RepositoryVisibility.PRIVATE);
        repository.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repository = repositoryRepository.save(repository);

        task = new Task();
        task.setProject(project);
        task.setRepository(repository);
        task.setCreatedBy(ownerUser);
        task.setTitle("Slice 14.1 Task");
        task.setDescription("Task for Slice 14.1 testing");
        task.setStatus(TaskStatus.COMPLETED);
        task.setPriority(TaskPriority.HIGH);
        task = taskRepository.save(task);

        taskExecution = new TaskExecution();
        taskExecution.setTask(task);
        taskExecution.setProject(project);
        taskExecution.setRepository(repository);
        taskExecution.setExecutionNumber(1);
        taskExecution.setStatus(ExecutionStatus.RUNNING);
        taskExecution.setEcosystem(Ecosystem.MAVEN);
        taskExecution = taskExecutionRepository.save(taskExecution);
    }

    @AfterEach
    void tearDown() {
        if (sandboxExecutionArtifactRepository != null) { sandboxExecutionArtifactRepository.deleteAll(); sandboxExecutionArtifactRepository.flush(); }
        if (sandboxJobExecutionRepository != null) { sandboxJobExecutionRepository.deleteAll(); sandboxJobExecutionRepository.flush(); }
        if (sandboxWorkerNodeRepository != null) { sandboxWorkerNodeRepository.deleteAll(); sandboxWorkerNodeRepository.flush(); }
        if (taskExecutionRepository != null) { taskExecutionRepository.deleteAll(); taskExecutionRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    @Test
    @DisplayName("1. Persist and reload valid SandboxWorkerNode entity")
    void test1_persistAndReloadSandboxWorkerNode() {
        SandboxWorkerNode node = new SandboxWorkerNode();
        node.setNodeIdentifier("node-worker-01");
        node.setSocketPath("/var/run/repopilot-sandbox.sock");
        node.setMaxConcurrentContainers(4);
        node.setActiveContainers(0);
        node.setStatus("HEALTHY");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        node.setLastHeartbeatAt(now);

        SandboxWorkerNode saved = sandboxWorkerNodeRepository.save(node);
        assertNotNull(saved.getId());

        SandboxWorkerNode reloaded = sandboxWorkerNodeRepository.findById(saved.getId()).orElseThrow();
        assertEquals("node-worker-01", reloaded.getNodeIdentifier());
        assertEquals("/var/run/repopilot-sandbox.sock", reloaded.getSocketPath());
        assertEquals(4, reloaded.getMaxConcurrentContainers());
        assertEquals(0, reloaded.getActiveContainers());
        assertEquals("HEALTHY", reloaded.getStatus());
        assertNotNull(reloaded.getCreatedAt());
    }

    @Test
    @DisplayName("2. Unique node identifier constraint enforcement")
    void test2_uniqueNodeIdentifierConstraint() {
        SandboxWorkerNode node1 = new SandboxWorkerNode();
        node1.setNodeIdentifier("node-dup-01");
        node1.setSocketPath("/var/run/sock1");
        sandboxWorkerNodeRepository.saveAndFlush(node1);

        SandboxWorkerNode node2 = new SandboxWorkerNode();
        node2.setNodeIdentifier("node-dup-01");
        node2.setSocketPath("/var/run/sock2");

        assertThrows(DataIntegrityViolationException.class, () -> {
            sandboxWorkerNodeRepository.saveAndFlush(node2);
        });
    }

    @Test
    @DisplayName("3. Persist and reload valid SandboxJobExecution entity with Enum, Timestamps, UUIDs")
    void test3_persistAndReloadSandboxJobExecution() {
        SandboxJobExecution job = new SandboxJobExecution();
        job.setTaskExecution(taskExecution);
        job.setProject(project);
        job.setRepository(repository);
        job.setStatus(SandboxJobStatus.PENDING);
        job.setCommandType("MAVEN_TEST");
        job.setCommandArgs("mvn test -Dtest=SecurityTest");
        job.setCpuLimitCores(2.0);
        job.setMemoryLimitMb(2048);
        job.setTimeoutSeconds(120);

        SandboxJobExecution saved = sandboxJobExecutionRepository.save(job);
        assertNotNull(saved.getId());
        assertNotNull(saved.getLeaseToken());
        assertEquals(1L, saved.getFencingVersion());
        assertEquals(1, saved.getAttemptCount());
        assertEquals(SandboxJobStatus.PENDING, saved.getStatus());

        SandboxJobExecution reloaded = sandboxJobExecutionRepository.findById(saved.getId()).orElseThrow();
        assertEquals(taskExecution.getId(), reloaded.getTaskExecution().getId());
        assertEquals(project.getId(), reloaded.getProject().getId());
        assertEquals(repository.getId(), reloaded.getRepository().getId());
        assertEquals("MAVEN_TEST", reloaded.getCommandType());
        assertEquals("mvn test -Dtest=SecurityTest", reloaded.getCommandArgs());
        assertEquals(2.0, reloaded.getCpuLimitCores());
        assertEquals(2048, reloaded.getMemoryLimitMb());
        assertEquals(120, reloaded.getTimeoutSeconds());
    }

    @Test
    @DisplayName("4. Unique constraint enforcement on (taskExecution, commandType)")
    void test4_uniqueTaskExecutionCommandTypeConstraint() {
        SandboxJobExecution job1 = new SandboxJobExecution();
        job1.setTaskExecution(taskExecution);
        job1.setProject(project);
        job1.setRepository(repository);
        job1.setCommandType("MAVEN_TEST");
        job1.setCommandArgs("mvn test");
        sandboxJobExecutionRepository.saveAndFlush(job1);

        SandboxJobExecution job2 = new SandboxJobExecution();
        job2.setTaskExecution(taskExecution);
        job2.setProject(project);
        job2.setRepository(repository);
        job2.setCommandType("MAVEN_TEST");
        job2.setCommandArgs("mvn test --repeat");

        assertThrows(DataIntegrityViolationException.class, () -> {
            sandboxJobExecutionRepository.saveAndFlush(job2);
        });
    }

    @Test
    @DisplayName("5. Persist and reload SandboxExecutionArtifact entity")
    void test5_persistAndReloadSandboxExecutionArtifact() {
        SandboxJobExecution job = new SandboxJobExecution();
        job.setTaskExecution(taskExecution);
        job.setProject(project);
        job.setRepository(repository);
        job.setCommandType("PYTEST");
        job.setCommandArgs("pytest tests/");
        job = sandboxJobExecutionRepository.save(job);

        SandboxExecutionArtifact artifact = new SandboxExecutionArtifact();
        artifact.setJobExecution(job);
        artifact.setArtifactType("PARSED_TEST_JSON");
        artifact.setContentHash("a".repeat(64));
        artifact.setRawOutput("raw test output");
        artifact.setSanitizedOutput("sanitized test output");
        artifact.setSecretScrubbedCount(2);

        SandboxExecutionArtifact saved = sandboxExecutionArtifactRepository.save(artifact);
        assertNotNull(saved.getId());

        List<SandboxExecutionArtifact> artifacts = sandboxExecutionArtifactRepository.findByJobExecutionId(job.getId());
        assertEquals(1, artifacts.size());
        assertEquals("PARSED_TEST_JSON", artifacts.get(0).getArtifactType());
        assertEquals("a".repeat(64), artifacts.get(0).getContentHash());
        assertEquals(2, artifacts.get(0).getSecretScrubbedCount());
    }

    @Test
    @DisplayName("6. Foreign key CASCADE deletion test (Deleting task_execution deletes sandbox_job_executions)")
    void test6_foreignKeyCascadeDeletion() {
        SandboxJobExecution job = new SandboxJobExecution();
        job.setTaskExecution(taskExecution);
        job.setProject(project);
        job.setRepository(repository);
        job.setCommandType("GO_TEST");
        job.setCommandArgs("go test ./...");
        job = sandboxJobExecutionRepository.saveAndFlush(job);

        UUID jobId = job.getId();
        assertTrue(sandboxJobExecutionRepository.existsById(jobId));

        taskExecutionRepository.delete(taskExecution);
        taskExecutionRepository.flush();

        assertFalse(sandboxJobExecutionRepository.existsById(jobId));
    }
}
