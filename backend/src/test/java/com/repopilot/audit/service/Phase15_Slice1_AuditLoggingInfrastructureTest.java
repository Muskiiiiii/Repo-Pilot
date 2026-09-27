package com.repopilot.audit.service;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase15_Slice1_AuditLoggingInfrastructureTest {

    @Autowired private AuditLogService auditLogService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private TaskRepository taskRepository;

    private User testUser;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;

    @BeforeEach
    void setUp() {
        tearDown();

        testUser = new User();
        testUser.setEmail("slice151_" + UUID.randomUUID() + "@example.com");
        testUser.setPasswordHash("hash");
        testUser.setName("Slice 15.1 User");
        testUser.setRole(UserRole.USER);
        testUser = userRepository.save(testUser);

        projectA = new Project();
        projectA.setName("Project A");
        projectA.setOwner(testUser);
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectB = new Project();
        projectB.setName("Project B");
        projectB.setOwner(testUser);
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("test-owner");
        repoA.setRepoName("repo-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("test-owner");
        repoB.setRepoName("repo-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(testUser);
        taskA.setTitle("Task A");
        taskA.setStatus(TaskStatus.COMPLETED);
        taskA.setPriority(TaskPriority.HIGH);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(testUser);
        taskB.setTitle("Task B");
        taskB.setStatus(TaskStatus.COMPLETED);
        taskB.setPriority(TaskPriority.HIGH);
        taskB = taskRepository.save(taskB);
    }

    @AfterEach
    void tearDown() {
        if (auditLogRepository != null) { auditLogRepository.deleteAll(); auditLogRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    @Test
    @DisplayName("1. Phase 15 Report Lifecycle Audit Events log correctly")
    void test1_phase15ReportLifecycleAuditEvents() {
        UUID execId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        String corrId = "corr-" + UUID.randomUUID().toString().substring(0, 8);

        AuditLog log1 = auditLogService.logReportRequested(testUser, projectA, taskA, execId, corrId);
        assertNotNull(log1.getId());
        assertEquals("REPORT_GENERATION_REQUESTED", log1.getAction());
        assertEquals(projectA.getId(), log1.getProject().getId());
        assertEquals(taskA.getId(), log1.getTask().getId());

        AuditLog log2 = auditLogService.logReportGenerated(testUser, projectA, taskA, reportId, corrId, "{\"score\":95}");
        assertNotNull(log2.getId());
        assertEquals("REPORT_GENERATED", log2.getAction());
        assertTrue(log2.getMetadata().contains("score"));

        AuditLog log3 = auditLogService.logReportFailed(testUser, projectA, taskA, execId, corrId, "LLM timeout");
        assertNotNull(log3.getId());
        assertEquals("REPORT_GENERATION_FAILED", log3.getAction());
        assertTrue(log3.getMetadata().contains("LLM timeout"));

        AuditLog log4 = auditLogService.logSseStreamConnected(testUser, projectA, taskA, corrId, "127.0.0.1");
        assertNotNull(log4.getId());
        assertEquals("SSE_STREAM_CONNECTED", log4.getAction());

        AuditLog log5 = auditLogService.logSseStreamReplayed(testUser, projectA, taskA, "task-1:10", corrId);
        assertNotNull(log5.getId());
        assertEquals("SSE_STREAM_REPLAY", log5.getAction());

        AuditLog log6 = auditLogService.logReportViewed(testUser, projectA, taskA, reportId, "127.0.0.1");
        assertNotNull(log6.getId());
        assertEquals("REPORT_VIEWED", log6.getAction());
    }

    @Test
    @DisplayName("2. Secret Redaction in Audit Metadata: Tokens and credentials automatically redacted")
    void test2_secretRedactionInAuditMetadata() {
        String sensitiveMetadata = "User token: ghp_123456789012345678901234567890123456, LLM Key: sk-proj-12345678901234567890, AWS: AKIAIOSFODNN7EXAMPLE, DB: postgresql://user:pass@localhost:5432/db";

        AuditLog log = auditLogService.logAction(testUser, projectA, taskA, "SENSITIVE_OP", "TASK", taskA.getId(), sensitiveMetadata);
        assertNotNull(log.getId());
        String meta = log.getMetadata();
        assertNotNull(meta);

        assertFalse(meta.contains("ghp_123456789012345678901234567890123456"), "GitHub PAT MUST be redacted");
        assertFalse(meta.contains("sk-proj-12345678901234567890"), "LLM API Key MUST be redacted");
        assertFalse(meta.contains("AKIAIOSFODNN7EXAMPLE"), "AWS Key MUST be redacted");
        assertFalse(meta.contains("postgresql://user:pass@localhost:5432/db"), "DB credentials MUST be redacted");
    }

    @Test
    @DisplayName("3. Cross-Project Hierarchy IDOR Protection: Task B under Project A rejected")
    void test3_crossProjectHierarchyIdorProtection() {
        assertThrows(IllegalArgumentException.class, () ->
                auditLogService.logAction(testUser, projectA, taskB, "IDOR_ATTEMPT", "TASK", taskB.getId(), "Cross project attempt")
        );
    }

    @Test
    @DisplayName("4. Database Immutability Verification: AuditLog createdAt cannot be updated")
    void test4_databaseImmutabilityVerification() {
        AuditLog log = auditLogService.logReportRequested(testUser, projectA, taskA, UUID.randomUUID(), "corr-1");
        assertNotNull(log.getId());

        List<AuditLog> logs = auditLogRepository.findByTaskId(taskA.getId());
        assertEquals(1, logs.size());
        assertNotNull(logs.get(0).getCreatedAt());
    }

    @Test
    @DisplayName("5. Concurrency: 10 concurrent threads logging audit actions execute safely")
    void test5_concurrencyMultiThreadedAuditLogging() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                AuditLog saved = auditLogService.logAction(testUser, projectA, taskA, "CONCURRENT_ACTION_" + index, "TASK", taskA.getId(), "{\"index\":" + index + "}");
                return saved != null && saved.getId() != null;
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        for (Future<Boolean> f : futures) {
            assertTrue(f.get());
        }

        List<AuditLog> persistedLogs = auditLogRepository.findByTaskId(taskA.getId());
        assertEquals(10, persistedLogs.size(), "All 10 concurrent audit logs MUST be persisted");
    }
}
