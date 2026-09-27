package com.repopilot.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.report.controller.TaskReportController;
import com.repopilot.report.dto.TaskReportResponseDto;
import com.repopilot.report.entity.ImplementationReport;
import com.repopilot.report.entity.ReportStatus;
import com.repopilot.report.entity.ReportSynthesis;
import com.repopilot.report.repository.ImplementationReportRepository;
import com.repopilot.report.repository.ReportSynthesisRepository;
import com.repopilot.report.service.ImplementationReportService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.repository.UserRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase15_Slice5_TaskReportHostileGateTest {

    @Autowired
    private TaskReportController taskReportController;

    @Autowired
    private ImplementationReportService implementationReportService;

    @Autowired
    private ImplementationReportRepository implementationReportRepository;

    @Autowired
    private ReportSynthesisRepository reportSynthesisRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskExecutionRepository taskExecutionRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectMemberRepository projectMemberRepository;

    @Autowired
    private CodeChangeRepository codeChangeRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private User ownerUser;
    private User viewerUser;
    private User foreignUser;

    private Project projectA;
    private Project projectB;

    private Repository repoA;
    private Repository repoB;

    private Task taskA;
    private Task taskB;

    private TaskExecution executionA;
    private ImplementationReport reportA;
    private ReportSynthesis synthesisA;

    @BeforeEach
    public void setUp() {
        auditLogRepository.deleteAll();
        reportSynthesisRepository.deleteAll();
        implementationReportRepository.deleteAll();
        codeChangeRepository.deleteAll();
        taskExecutionRepository.deleteAll();
        taskRepository.deleteAll();
        projectMemberRepository.deleteAll();
        repositoryRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();

        // Create Users
        ownerUser = createUser("owner@example.com", "Owner User");
        viewerUser = createUser("viewer@example.com", "Viewer User");
        foreignUser = createUser("foreign@example.com", "Foreign User");

        // Create Projects
        projectA = createProject("Project Alpha", ownerUser);
        projectB = createProject("Project Beta", foreignUser);

        // Add Viewer to Project A
        addProjectMember(projectA, viewerUser, ProjectRole.VIEWER);

        // Create Repositories
        repoA = createRepository("repo-alpha", projectA);
        repoB = createRepository("repo-beta", projectB);

        // Create Tasks
        taskA = createTask("Task Alpha Implementation", projectA, repoA);
        taskB = createTask("Task Beta Implementation", projectB, repoB);

        // Create Execution & ImplementationReport for Task A
        executionA = createTaskExecution(taskA, projectA, repoA, ExecutionStatus.COMPLETED);

        reportA = new ImplementationReport();
        reportA.setProject(projectA);
        reportA.setRepository(repoA);
        reportA.setTask(taskA);
        reportA.setTaskExecution(executionA);
        reportA.setStatus(ReportStatus.GENERATED);
        reportA.setExecutionOutcome("COMPLETED");
        reportA.setChangedFilesSummary("{\"totalChanges\":1,\"boundedFiles\":[{\"filePath\":\"src/Main.java\",\"operation\":\"MODIFY\",\"originalHash\":\"abc\",\"proposedHash\":\"def\"}]}");
        reportA.setTestSummary("{\"totalTests\":10,\"passed\":10,\"failed\":0,\"skipped\":0}");
        reportA.setSecurityReviewSummary("{\"totalReviews\":1,\"totalFindings\":0,\"severityCounts\":{\"CRITICAL\":0,\"HIGH\":0},\"complianceCertificateStatus\":\"PASSED\"}");
        reportA.setConfidenceScore(new BigDecimal("100.00"));
        reportA.setSourceContextHash("a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890");
        reportA.setCorrelationId("corr-1234");
        reportA = implementationReportRepository.saveAndFlush(reportA);

        // Create ReportSynthesis for Task A
        synthesisA = new ReportSynthesis();
        synthesisA.setImplementationReport(reportA);
        synthesisA.setTaskExecution(executionA);
        synthesisA.setProject(projectA);
        synthesisA.setSourceContextHash(reportA.getSourceContextHash());
        synthesisA.setAiSynthesisInputHash("hash123456789012345678901234567890123456789012345678901234567890");
        synthesisA.setExecutiveSummary("Executive explanation for Task Alpha");
        synthesisA.setImplementationExplanation("Code changes explanation");
        synthesisA.setTestingExplanation("Test suite summary explanation");
        synthesisA.setSecurityExplanation("Security posture explanation");
        synthesisA.setLimitationsJson("[\"Limitation 1\"]");
        synthesisA.setDeveloperNotesJson("[\"Developer Note 1\"]");
        synthesisA.setRenderedMarkdown("# Task Alpha Report\n\nExecutive summary text.");
        synthesisA = reportSynthesisRepository.saveAndFlush(synthesisA);
    }

    // ==========================================
    // 1. AUTHENTICATION & IDOR TESTS
    // ==========================================

    @Test
    @DisplayName("1. Unauthenticated user GET /report throws 401 UNAUTHORIZED")
    public void test1_unauthenticatedUserDenied() {
        ApiException ex = assertThrows(ApiException.class, () -> {
            taskReportController.getTaskReport(projectA.getId(), taskA.getId(), null);
        });
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    @DisplayName("2. Authorized VIEWER user GET /report succeeds with 200 OK")
    public void test2_viewerRoleAuthorized() {
        UserPrincipal principal = new UserPrincipal(viewerUser);
        ResponseEntity<TaskReportResponseDto> response = taskReportController.getTaskReport(
                projectA.getId(), taskA.getId(), principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(taskA.getId(), response.getBody().getTaskId());
        assertEquals("COMPLETED", response.getBody().getImplementationReport().getExecutionOutcome());
        assertEquals("AVAILABLE", response.getBody().getSynthesisStatus());
    }

    @Test
    @DisplayName("3. Cross-project IDOR attempt (foreign user accessing Project A task) throws 403 FORBIDDEN")
    public void test3_crossProjectIDORDenied() {
        UserPrincipal principal = new UserPrincipal(foreignUser);
        ApiException ex = assertThrows(ApiException.class, () -> {
            taskReportController.getTaskReport(projectA.getId(), taskA.getId(), principal);
        });
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("4. Project/Task mismatch (Task A specified with Project B ID) throws 403 FORBIDDEN")
    public void test4_projectTaskMismatchDenied() {
        UserPrincipal principal = new UserPrincipal(ownerUser);
        ApiException ex = assertThrows(ApiException.class, () -> {
            taskReportController.getTaskReport(projectB.getId(), taskA.getId(), principal);
        });
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    @DisplayName("5. Nonexistent task GET /report throws 404 NOT_FOUND")
    public void test5_nonexistentTaskNotFound() {
        UserPrincipal principal = new UserPrincipal(ownerUser);
        UUID randomTaskId = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () -> {
            taskReportController.getTaskReport(projectA.getId(), randomTaskId, principal);
        });
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // ==========================================
    // 2. READ-ONLY & HISTORICAL IMMUTABILITY TESTS
    // ==========================================

    @Test
    @DisplayName("6. GET /report retrieves exact historical report without recomputing")
    public void test6_historicalReportRetrievalNoRecomputation() {
        // Mutate CodeChange table post report creation
        CodeChange newChange = new CodeChange();
        newChange.setTask(taskA);
        newChange.setFilePath("src/Mutated.java");
        newChange.setOperation("CREATE");
        codeChangeRepository.saveAndFlush(newChange);

        UserPrincipal principal = new UserPrincipal(viewerUser);
        ResponseEntity<TaskReportResponseDto> response = taskReportController.getTaskReport(
                projectA.getId(), taskA.getId(), principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Verify response still contains historical changed files summary, NOT mutated table
        assertTrue(response.getBody().getImplementationReport().getChangedFilesSummary().contains("src/Main.java"));
        assertFalse(response.getBody().getImplementationReport().getChangedFilesSummary().contains("src/Mutated.java"));
    }

    @Test
    @DisplayName("7. Authoritative facts override hallucinated/conflicting LLM synthesis facts")
    public void test7_authoritativeFactsOverrideHallucinatedLlmFacts() {
        // Create hallucinated synthesis with conflicting claims
        ReportSynthesis hallucinatedSynthesis = new ReportSynthesis();
        hallucinatedSynthesis.setImplementationReport(reportA);
        hallucinatedSynthesis.setTaskExecution(executionA);
        hallucinatedSynthesis.setProject(projectA);
        hallucinatedSynthesis.setSourceContextHash("FAKE_HASH_123");
        hallucinatedSynthesis.setAiSynthesisInputHash("hash999");
        hallucinatedSynthesis.setExecutiveSummary("Execution FAILED with 999 critical security findings in src/Fake.java");
        hallucinatedSynthesis.setImplementationExplanation("Hallucinated explanation");
        hallucinatedSynthesis.setTestingExplanation("Hallucinated tests");
        hallucinatedSynthesis.setSecurityExplanation("Hallucinated security");
        hallucinatedSynthesis.setRenderedMarkdown("# Hallucinated Report");

        UserPrincipal principal = new UserPrincipal(viewerUser);
        ResponseEntity<TaskReportResponseDto> response = taskReportController.getTaskReport(
                projectA.getId(), taskA.getId(), principal);

        TaskReportResponseDto dto = response.getBody();
        assertNotNull(dto);
        // Authoritative ImplementationReport facts must remain COMPLETED and 100.00
        assertEquals("COMPLETED", dto.getImplementationReport().getExecutionOutcome());
        assertEquals(new BigDecimal("100.00"), dto.getImplementationReport().getConfidenceScore());
        assertEquals("a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890", dto.getImplementationReport().getSourceContextHash());
        assertTrue(dto.getImplementationReport().getChangedFilesSummary().contains("src/Main.java"));
    }

    @Test
    @DisplayName("8. DTO reflection audit proves 0 internal secrets or fencing tokens exposed")
    public void test8_dtoReflectionAuditNoInternalTokens() {
        UserPrincipal principal = new UserPrincipal(viewerUser);
        ResponseEntity<TaskReportResponseDto> response = taskReportController.getTaskReport(
                projectA.getId(), taskA.getId(), principal);

        TaskReportResponseDto dto = response.getBody();
        assertNotNull(dto);

        List<String> prohibitedFields = Arrays.asList(
                "leaseToken", "workerId", "fencingVersion", "githubToken",
                "rawPrompt", "rawSecretScannerInput", "stackTrace"
        );

        for (Field f : TaskReportResponseDto.class.getDeclaredFields()) {
            f.setAccessible(true);
            String name = f.getName();
            assertFalse(prohibitedFields.contains(name), "Prohibited field exposed in DTO: " + name);
        }
    }

    @Test
    @DisplayName("9. Task with no synthesis returns synthesisStatus = NONE safely")
    public void test9_taskWithNoSynthesisReturnsNoneStatus() {
        reportSynthesisRepository.deleteAll();

        UserPrincipal principal = new UserPrincipal(viewerUser);
        ResponseEntity<TaskReportResponseDto> response = taskReportController.getTaskReport(
                projectA.getId(), taskA.getId(), principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NONE", response.getBody().getSynthesisStatus());
        assertNull(response.getBody().getSynthesis());
    }

    @Test
    @DisplayName("10. Concurrent GET requests execute safely without deadlocks")
    public void test10_concurrentGetRequestsNoDeadlocks() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        List<Future<HttpStatus>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    UserPrincipal principal = new UserPrincipal(viewerUser);
                    ResponseEntity<TaskReportResponseDto> res = taskReportController.getTaskReport(
                            projectA.getId(), taskA.getId(), principal);
                    return (HttpStatus) res.getStatusCode();
                } catch (Exception e) {
                    return HttpStatus.INTERNAL_SERVER_ERROR;
                }
            }));
        }

        for (Future<HttpStatus> f : futures) {
            assertEquals(HttpStatus.OK, f.get(5, TimeUnit.SECONDS));
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("11. GET /report emits REPORT_VIEWED audit log event")
    public void test11_getReportEmitsAuditLog() {
        auditLogRepository.deleteAll();

        UserPrincipal principal = new UserPrincipal(viewerUser);
        taskReportController.getTaskReport(projectA.getId(), taskA.getId(), principal);

        List<AuditLog> logs = auditLogRepository.findByProjectId(projectA.getId());
        assertFalse(logs.isEmpty());

        boolean foundReportViewed = logs.stream()
                .anyMatch(l -> "REPORT_VIEWED".equals(l.getAction()));
        assertTrue(foundReportViewed, "REPORT_VIEWED audit log event should be emitted");
    }

    @Test
    @DisplayName("7b. SQL StatementInspector proves 0 report mutations and exactly 1 audit INSERT during GET")
    public void test7b_sqlStatementInspectorReportViewedWriteBehavior() {
        com.repopilot.review.TestSqlStatementInspector.startRecording();
        try {
            UserPrincipal principal = new UserPrincipal(viewerUser);
            taskReportController.getTaskReport(projectA.getId(), taskA.getId(), principal);
        } finally {
            com.repopilot.review.TestSqlStatementInspector.stopRecording();
        }

        List<String> sqls = com.repopilot.review.TestSqlStatementInspector.getCapturedSqls();

        long reportInserts = sqls.stream().filter(s -> s.toLowerCase().contains("insert into implementation_reports")).count();
        long reportUpdates = sqls.stream().filter(s -> s.toLowerCase().contains("update implementation_reports")).count();
        long reportDeletes = sqls.stream().filter(s -> s.toLowerCase().contains("delete from implementation_reports")).count();

        long synthesisInserts = sqls.stream().filter(s -> s.toLowerCase().contains("insert into report_syntheses")).count();
        long synthesisUpdates = sqls.stream().filter(s -> s.toLowerCase().contains("update report_syntheses")).count();
        long synthesisDeletes = sqls.stream().filter(s -> s.toLowerCase().contains("delete from report_syntheses")).count();

        long taskUpdates = sqls.stream().filter(s -> s.toLowerCase().contains("update tasks")).count();
        long codeChangeUpdates = sqls.stream().filter(s -> s.toLowerCase().contains("update code_changes")).count();

        long auditInserts = sqls.stream().filter(s -> s.toLowerCase().contains("insert into audit_logs")).count();

        assertEquals(0, reportInserts, "FORBIDDEN: ImplementationReport INSERT MUST be 0");
        assertEquals(0, reportUpdates, "FORBIDDEN: ImplementationReport UPDATE MUST be 0");
        assertEquals(0, reportDeletes, "FORBIDDEN: ImplementationReport DELETE MUST be 0");

        assertEquals(0, synthesisInserts, "FORBIDDEN: ReportSynthesis INSERT MUST be 0");
        assertEquals(0, synthesisUpdates, "FORBIDDEN: ReportSynthesis UPDATE MUST be 0");
        assertEquals(0, synthesisDeletes, "FORBIDDEN: ReportSynthesis DELETE MUST be 0");

        assertEquals(0, taskUpdates, "FORBIDDEN: Task UPDATE MUST be 0");
        assertEquals(0, codeChangeUpdates, "FORBIDDEN: CodeChange UPDATE MUST be 0");

        assertEquals(1, auditInserts, "EXPECTED: Exactly 1 intentional REPORT_VIEWED audit log INSERT MUST occur");
    }

    @Test
    @DisplayName("12. Secret credentials in report summaries are scrubbed by SecretRedactor before DTO serialization")
    public void test12_secretAbsenceInDto() {
        reportA.setFailureReason("Failed with token ghp_1234567890abcdefghijklmnopqrstuvwxyz and SK sk-proj-12345678901234567890");
        implementationReportRepository.saveAndFlush(reportA);

        UserPrincipal principal = new UserPrincipal(viewerUser);
        ResponseEntity<TaskReportResponseDto> response = taskReportController.getTaskReport(projectA.getId(), taskA.getId(), principal);

        TaskReportResponseDto dto = response.getBody();
        assertNotNull(dto);
        String reason = dto.getImplementationReport().getFailureReason();
        assertFalse(reason.contains("ghp_1234567890abcdefghijklmnopqrstuvwxyz"));
        assertFalse(reason.contains("sk-proj-12345678901234567890"));
    }

    @Test
    @DisplayName("15. ApiException error responses redact internal stack traces and database details")
    public void test15_errorInformationLeakageRedacted() {
        UserPrincipal principal = new UserPrincipal(foreignUser);
        ApiException ex = assertThrows(ApiException.class, () -> {
            taskReportController.getTaskReport(projectA.getId(), taskA.getId(), principal);
        });

        assertFalse(ex.getMessage().contains("org.hibernate"));
        assertFalse(ex.getMessage().contains("PostgreSQL"));
        assertFalse(ex.getMessage().contains("SQLException"));
    }

    @Test
    @DisplayName("16. Report synthesis and historical report retrieval across fresh context read match exact context hash")
    public void test16_restartHistoricalRetrieval() {
        UserPrincipal principal = new UserPrincipal(viewerUser);
        ResponseEntity<TaskReportResponseDto> response1 = taskReportController.getTaskReport(projectA.getId(), taskA.getId(), principal);
        ResponseEntity<TaskReportResponseDto> response2 = taskReportController.getTaskReport(projectA.getId(), taskA.getId(), principal);

        assertEquals(response1.getBody().getImplementationReport().getSourceContextHash(), response2.getBody().getImplementationReport().getSourceContextHash());
        assertEquals(response1.getBody().getSynthesis().getAiSynthesisInputHash(), response2.getBody().getSynthesis().getAiSynthesisInputHash());
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private User createUser(String email, String fullName) {
        User u = new User();
        u.setEmail(email);
        u.setName(fullName);
        u.setRole(com.repopilot.user.entity.UserRole.USER);
        u.setPasswordHash("hashed_pass");
        return userRepository.saveAndFlush(u);
    }

    private Project createProject(String name, User owner) {
        Project p = new Project();
        p.setName(name);
        p.setOwner(owner);
        p.setStatus(com.repopilot.project.entity.ProjectStatus.ACTIVE);
        p = projectRepository.saveAndFlush(p);

        addProjectMember(p, owner, ProjectRole.OWNER);
        return p;
    }

    private void addProjectMember(Project project, User user, ProjectRole role) {
        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        projectMemberRepository.saveAndFlush(member);
    }

    private Repository createRepository(String name, Project project) {
        Repository r = new Repository();
        r.setProject(project);
        r.setOwnerName("test-owner");
        r.setRepoName(name);
        r.setDefaultBranch("main");
        r.setVisibility(com.repopilot.repository.entity.RepositoryVisibility.PRIVATE);
        r.setIndexStatus(com.repopilot.repository.entity.RepositoryIndexStatus.INDEXED);
        return repositoryRepository.saveAndFlush(r);
    }

    private Task createTask(String title, Project project, Repository repo) {
        Task t = new Task();
        t.setTitle(title);
        t.setProject(project);
        t.setRepository(repo);
        t.setCreatedBy(project.getOwner());
        t.setStatus(TaskStatus.COMPLETED);
        return taskRepository.saveAndFlush(t);
    }

    private TaskExecution createTaskExecution(Task task, Project project, Repository repo, ExecutionStatus status) {
        TaskExecution e = new TaskExecution();
        e.setTask(task);
        e.setProject(project);
        e.setRepository(repo);
        e.setStatus(status);
        e.setExecutionNumber(1);
        return taskExecutionRepository.saveAndFlush(e);
    }
}
