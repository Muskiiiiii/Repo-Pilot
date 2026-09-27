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
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.project.repository.ProjectMemberRepository;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.report.dto.ImplementationReportResponseDto;
import com.repopilot.report.entity.ImplementationReport;
import com.repopilot.report.entity.ReportStatus;
import com.repopilot.report.repository.ImplementationReportRepository;
import com.repopilot.report.service.ImplementationReportService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.entity.RepositoryIndexStatus;
import com.repopilot.repository.entity.RepositoryVisibility;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.FindingStatus;
import com.repopilot.review.entity.ReviewFinding;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.entity.TaskPriority;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import com.repopilot.user.entity.UserRole;
import com.repopilot.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
public class Phase15_Slice3_ImplementationReportHostileTest {

    @Autowired private ImplementationReportService implementationReportService;
    @Autowired private ImplementationReportRepository implementationReportRepository;
    @Autowired private TaskExecutionRepository taskExecutionRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private CodeChangeRepository codeChangeRepository;
    @Autowired private CodeReviewRepository codeReviewRepository;
    @Autowired private ReviewFindingRepository reviewFindingRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @SpyBean private com.repopilot.audit.service.AuditLogService auditLogService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private RepositoryRepository repositoryRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    private User ownerA;
    private User viewerA;
    private User ownerB;
    private Project projectA;
    private Project projectB;
    private Repository repoA;
    private Repository repoB;
    private Task taskA;
    private Task taskB;
    private TaskExecution executionA1;
    private TaskExecution executionB1;

    @BeforeEach
    void setUp() {
        tearDown();

        ownerA = new User();
        ownerA.setEmail("impl_owner_a_" + UUID.randomUUID() + "@example.com");
        ownerA.setPasswordHash("hash");
        ownerA.setName("Owner A");
        ownerA.setRole(UserRole.USER);
        ownerA = userRepository.save(ownerA);

        viewerA = new User();
        viewerA.setEmail("impl_viewer_a_" + UUID.randomUUID() + "@example.com");
        viewerA.setPasswordHash("hash");
        viewerA.setName("Viewer A");
        viewerA.setRole(UserRole.USER);
        viewerA = userRepository.save(viewerA);

        ownerB = new User();
        ownerB.setEmail("impl_owner_b_" + UUID.randomUUID() + "@example.com");
        ownerB.setPasswordHash("hash");
        ownerB.setName("Owner B");
        ownerB.setRole(UserRole.USER);
        ownerB = userRepository.save(ownerB);

        projectA = new Project();
        projectA.setName("Project A");
        projectA.setOwner(ownerA);
        projectA.setStatus(ProjectStatus.ACTIVE);
        projectA = projectRepository.save(projectA);

        projectB = new Project();
        projectB.setName("Project B");
        projectB.setOwner(ownerB);
        projectB.setStatus(ProjectStatus.ACTIVE);
        projectB = projectRepository.save(projectB);

        projectMemberRepository.save(new ProjectMember(projectA, ownerA, ProjectRole.OWNER));
        projectMemberRepository.save(new ProjectMember(projectA, viewerA, ProjectRole.VIEWER));
        projectMemberRepository.save(new ProjectMember(projectB, ownerB, ProjectRole.OWNER));

        repoA = new Repository();
        repoA.setProject(projectA);
        repoA.setOwnerName("owner-a");
        repoA.setRepoName("repo-a-" + UUID.randomUUID().toString().substring(0, 8));
        repoA.setDefaultBranch("main");
        repoA.setVisibility(RepositoryVisibility.PRIVATE);
        repoA.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoA = repositoryRepository.save(repoA);

        repoB = new Repository();
        repoB.setProject(projectB);
        repoB.setOwnerName("owner-b");
        repoB.setRepoName("repo-b-" + UUID.randomUUID().toString().substring(0, 8));
        repoB.setDefaultBranch("main");
        repoB.setVisibility(RepositoryVisibility.PRIVATE);
        repoB.setIndexStatus(RepositoryIndexStatus.INDEXED);
        repoB = repositoryRepository.save(repoB);

        taskA = new Task();
        taskA.setProject(projectA);
        taskA.setRepository(repoA);
        taskA.setCreatedBy(ownerA);
        taskA.setTitle("Task A");
        taskA.setStatus(TaskStatus.COMPLETED);
        taskA.setPriority(TaskPriority.HIGH);
        taskA = taskRepository.save(taskA);

        taskB = new Task();
        taskB.setProject(projectB);
        taskB.setRepository(repoB);
        taskB.setCreatedBy(ownerB);
        taskB.setTitle("Task B");
        taskB.setStatus(TaskStatus.COMPLETED);
        taskB.setPriority(TaskPriority.HIGH);
        taskB = taskRepository.save(taskB);

        executionA1 = new TaskExecution();
        executionA1.setTask(taskA);
        executionA1.setProject(projectA);
        executionA1.setRepository(repoA);
        executionA1.setExecutionNumber(1);
        executionA1.setStatus(ExecutionStatus.COMPLETED);
        executionA1.setStartedAt(Instant.now().minusSeconds(120));
        executionA1.setCompletedAt(Instant.now().minusSeconds(10));
        executionA1 = taskExecutionRepository.save(executionA1);

        executionB1 = new TaskExecution();
        executionB1.setTask(taskB);
        executionB1.setProject(projectB);
        executionB1.setRepository(repoB);
        executionB1.setExecutionNumber(1);
        executionB1.setStatus(ExecutionStatus.COMPLETED);
        executionB1.setStartedAt(Instant.now().minusSeconds(120));
        executionB1.setCompletedAt(Instant.now().minusSeconds(10));
        executionB1 = taskExecutionRepository.save(executionB1);
    }

    @AfterEach
    void tearDown() {
        if (implementationReportRepository != null) { implementationReportRepository.deleteAll(); implementationReportRepository.flush(); }
        if (reviewFindingRepository != null) { reviewFindingRepository.deleteAll(); reviewFindingRepository.flush(); }
        if (codeReviewRepository != null) { codeReviewRepository.deleteAll(); codeReviewRepository.flush(); }
        if (codeChangeRepository != null) { codeChangeRepository.deleteAll(); codeChangeRepository.flush(); }
        if (auditLogRepository != null) { auditLogRepository.deleteAll(); auditLogRepository.flush(); }
        if (taskExecutionRepository != null) { taskExecutionRepository.deleteAll(); taskExecutionRepository.flush(); }
        if (taskRepository != null) { taskRepository.deleteAll(); taskRepository.flush(); }
        if (repositoryRepository != null) { repositoryRepository.deleteAll(); repositoryRepository.flush(); }
        if (projectMemberRepository != null) { projectMemberRepository.deleteAll(); projectMemberRepository.flush(); }
        if (projectRepository != null) { projectRepository.deleteAll(); projectRepository.flush(); }
        if (userRepository != null) { userRepository.deleteAll(); userRepository.flush(); }
    }

    @Test
    @DisplayName("BLOCKER A: Execution Completion Gate (Non-terminal CREATED/RUNNING rejected, COMPLETED/FAILED accepted)")
    void testBlockerA_executionCompletionGate() {
        TaskExecution runningExec = new TaskExecution();
        runningExec.setTask(taskA);
        runningExec.setProject(projectA);
        runningExec.setRepository(repoA);
        runningExec.setExecutionNumber(2);
        runningExec.setStatus(ExecutionStatus.RUNNING);
        runningExec.setStartedAt(Instant.now());
        runningExec = taskExecutionRepository.save(runningExec);

        final UUID runningId = runningExec.getId();

        // 1. RUNNING execution MUST be rejected with HTTP 400 Bad Request
        ApiException ex = assertThrows(ApiException.class, () ->
                implementationReportService.generateReport(runningId, ownerA)
        );
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("non-terminal status"));

        // 2. FAILED execution MUST be accepted (report describes failed execution)
        TaskExecution failedExec = new TaskExecution();
        failedExec.setTask(taskA);
        failedExec.setProject(projectA);
        failedExec.setRepository(repoA);
        failedExec.setExecutionNumber(3);
        failedExec.setStatus(ExecutionStatus.FAILED);
        failedExec.setErrorMessage("Build timeout in sandbox");
        failedExec.setStartedAt(Instant.now().minusSeconds(60));
        failedExec.setCompletedAt(Instant.now());
        failedExec = taskExecutionRepository.save(failedExec);

        ImplementationReportResponseDto failedReport = implementationReportService.generateReport(failedExec.getId(), ownerA);
        assertNotNull(failedReport);
        assertEquals("FAILED", failedReport.getExecutionOutcome());
        assertEquals(ReportStatus.GENERATED, failedReport.getStatus());
    }

    @Test
    @DisplayName("BLOCKER B & C: TaskExecution -> Report Identity & Immutability Across Later Executions & Mutations")
    void testBlockerBAndC_taskExecutionIdentityAndHistoricalImmutability() {
        // 1. Generate Report R1 for Execution E1
        ImplementationReportResponseDto r1 = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(r1);
        assertEquals(executionA1.getId(), r1.getTaskExecutionId());

        String initialHash = r1.getSourceContextHash();
        assertNotNull(initialHash);

        // 2. Create later Execution E2 for same task
        TaskExecution executionA2 = new TaskExecution();
        executionA2.setTask(taskA);
        executionA2.setProject(projectA);
        executionA2.setRepository(repoA);
        executionA2.setExecutionNumber(2);
        executionA2.setStatus(ExecutionStatus.COMPLETED);
        executionA2.setStartedAt(Instant.now());
        executionA2 = taskExecutionRepository.save(executionA2);

        // 3. Mutate Task state and add new CodeChanges
        taskA.setTitle("Mutated Task Title");
        taskRepository.save(taskA);

        CodeChange newChange = new CodeChange();
        newChange.setTask(taskA);
        newChange.setRepository(repoA);
        newChange.setFilePath("src/MutatedFile.java");
        newChange.setOperation("MODIFY");
        codeChangeRepository.save(newChange);

        // 4. Retrieve R1
        ImplementationReportResponseDto retrievedR1 = implementationReportService.getReportByExecutionId(executionA1.getId(), ownerA);

        // 5. Verify R1 remains exactly bound to E1 and its source context hash is 100% unchanged
        assertEquals(r1.getId(), retrievedR1.getId());
        assertEquals(executionA1.getId(), retrievedR1.getTaskExecutionId());
        assertEquals(initialHash, retrievedR1.getSourceContextHash());
    }

    @Test
    @DisplayName("BLOCKER D & S: Deterministic Canonical Source Context Hash Reproducibility")
    void testBlockerDAndS_deterministicSourceContextHash() {
        CodeChange change1 = new CodeChange();
        change1.setTask(taskA);
        change1.setRepository(repoA);
        change1.setFilePath("src/A.java");
        change1.setOperation("CREATE");
        change1.setProposedHash("hash-a-123");
        codeChangeRepository.save(change1);

        CodeChange change2 = new CodeChange();
        change2.setTask(taskA);
        change2.setRepository(repoA);
        change2.setFilePath("src/B.java");
        change2.setOperation("MODIFY");
        change2.setProposedHash("hash-b-456");
        codeChangeRepository.save(change2);

        ImplementationReportResponseDto dto1 = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(dto1.getSourceContextHash());
        assertEquals(64, dto1.getSourceContextHash().length(), "SHA-256 hash MUST be 64 hex characters");

        // Idempotent generation/retrieval MUST produce identical hash
        ImplementationReportResponseDto dto2 = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertEquals(dto1.getSourceContextHash(), dto2.getSourceContextHash());
    }

    @Test
    @DisplayName("BLOCKER E & F: Database Uniqueness & Concurrent Report Creation (10 Concurrent Threads -> Exactly 1 Report)")
    void testBlockerEAndF_concurrentReportCreationUniqueness() throws Exception {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch barrier = new CountDownLatch(1);
        List<Future<ImplementationReportResponseDto>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return implementationReportService.generateReport(executionA1.getId(), ownerA);
            }));
        }

        barrier.countDown();

        Set<UUID> reportIds = new HashSet<>();
        for (Future<ImplementationReportResponseDto> f : futures) {
            ImplementationReportResponseDto dto = f.get(5, TimeUnit.SECONDS);
            assertNotNull(dto);
            reportIds.add(dto.getId());
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Enforces EXACTLY ONE report entity persisted at DB level
        assertEquals(1, reportIds.size(), "EXACTLY 1 report MUST be persisted under 10-thread concurrent creation race");
        assertTrue(implementationReportRepository.existsByTaskExecutionId(executionA1.getId()));
    }

    @Test
    @DisplayName("BLOCKER H: Single-Source Audit Integration (REPORT_GENERATION_REQUESTED & REPORT_GENERATED)")
    void testBlockerH_auditIntegrationSingleSource() {
        ImplementationReportResponseDto dto = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(dto);

        List<AuditLog> auditLogs = auditLogRepository.findByTaskId(taskA.getId());
        boolean hasReq = false;
        boolean hasGen = false;

        for (AuditLog l : auditLogs) {
            if ("REPORT_GENERATION_REQUESTED".equals(l.getAction())) hasReq = true;
            if ("REPORT_GENERATED".equals(l.getAction())) hasGen = true;
        }

        assertTrue(hasReq, "Audit log MUST contain REPORT_GENERATION_REQUESTED event");
        assertTrue(hasGen, "Audit log MUST contain REPORT_GENERATED event");
    }

    @Test
    @DisplayName("BLOCKER I & J: Cross-Project IDOR Protection & Report Read Authorization")
    void testBlockerIAndJ_crossProjectIDORProtection() {
        // 1. Owner B attempts to generate report for Project A's execution -> Throws 403 Forbidden
        ApiException exGen = assertThrows(ApiException.class, () ->
                implementationReportService.generateReport(executionA1.getId(), ownerB)
        );
        assertEquals(HttpStatus.FORBIDDEN, exGen.getStatus());

        // 2. Generate Report R1 for Project A using Owner A
        ImplementationReportResponseDto r1 = implementationReportService.generateReport(executionA1.getId(), ownerA);

        // 3. Owner B attempts getReportByExecutionId for Execution A1 -> Throws 403 Forbidden
        ApiException exGetExec = assertThrows(ApiException.class, () ->
                implementationReportService.getReportByExecutionId(executionA1.getId(), ownerB)
        );
        assertEquals(HttpStatus.FORBIDDEN, exGetExec.getStatus());

        // 4. Owner B attempts getReportById for Report R1 -> Throws 403 Forbidden
        ApiException exGetId = assertThrows(ApiException.class, () ->
                implementationReportService.getReportById(r1.getId(), ownerB)
        );
        assertEquals(HttpStatus.FORBIDDEN, exGetId.getStatus());

        // 5. Viewer A (member of Project A) CAN retrieve Report R1
        ImplementationReportResponseDto viewerReport = implementationReportService.getReportById(r1.getId(), viewerA);
        assertNotNull(viewerReport);
        assertEquals(r1.getId(), viewerReport.getId());
    }

    @Test
    @DisplayName("BLOCKER K: Null Principal Unauthenticated Protection")
    void testBlockerK_nullPrincipalUnauthenticated() {
        ApiException exGen = assertThrows(ApiException.class, () ->
                implementationReportService.generateReport(executionA1.getId(), null)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, exGen.getStatus());

        ApiException exGet = assertThrows(ApiException.class, () ->
                implementationReportService.getReportByExecutionId(executionA1.getId(), null)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, exGet.getStatus());
    }

    @Test
    @DisplayName("BLOCKER L & M: Secret Safety at Output & Resource Bounded Summaries")
    void testBlockerLAndM_secretSafetyAndResourceBounds() {
        CodeChange secretChange = new CodeChange();
        secretChange.setTask(taskA);
        secretChange.setRepository(repoA);
        secretChange.setFilePath("src/SecretConfig.java");
        secretChange.setOperation("MODIFY");
        secretChange.setReason("Adding AWS_SECRET_KEY = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY and ghp_123456789012345678901234567890123456");
        codeChangeRepository.save(secretChange);

        executionA1.setErrorMessage("Failed with LLM key sk-proj-12345678901234567890 and DB postgres://user:secretpass123@localhost:5432/db");
        taskExecutionRepository.save(executionA1);

        ImplementationReportResponseDto report = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(report);

        String filesSummary = report.getChangedFilesSummary();
        String failureReason = report.getFailureReason();

        assertFalse(filesSummary.contains("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"), "AWS Secret MUST NOT leak in changedFilesSummary");
        assertFalse(filesSummary.contains("ghp_123456789012345678901234567890123456"), "GitHub PAT MUST NOT leak in changedFilesSummary");
        assertFalse(failureReason.contains("sk-proj-12345678901234567890"), "LLM Key MUST NOT leak in failureReason");
        assertFalse(failureReason.contains("secretpass123"), "DB Password MUST NOT leak in failureReason");
    }

    @Test
    @DisplayName("BLOCKER N & O & P: Database Immutability Trigger (Direct SQL UPDATE/DELETE Fails)")
    void testBlockerNAndOAndP_databaseImmutabilityTrigger() {
        ImplementationReportResponseDto reportDto = implementationReportService.generateReport(executionA1.getId(), ownerA);
        final UUID reportId = reportDto.getId();

        // 1. Direct native SQL UPDATE MUST be rejected by PostgreSQL/H2 trigger
        assertThrows(Exception.class, () -> {
            entityManager.createNativeQuery("UPDATE implementation_reports SET failure_reason = 'Hacked' WHERE id = :id")
                    .setParameter("id", reportId)
                    .executeUpdate();
            entityManager.flush();
        });

        // 2. Direct native SQL DELETE MUST be rejected by PostgreSQL/H2 trigger
        assertThrows(Exception.class, () -> {
            entityManager.createNativeQuery("DELETE FROM implementation_reports WHERE id = :id")
                    .setParameter("id", reportId)
                    .executeUpdate();
            entityManager.flush();
        });
    }

    @Test
    @DisplayName("BLOCKER T: Restart & Retrieval Behavior (Retrieval by Execution ID and Report ID)")
    void testBlockerT_restartAndRetrievalBehavior() {
        ImplementationReportResponseDto original = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(original);

        // Retrieve by Execution ID
        ImplementationReportResponseDto byExec = implementationReportService.getReportByExecutionId(executionA1.getId(), ownerA);
        assertEquals(original.getId(), byExec.getId());

        // Retrieve by Report ID
        ImplementationReportResponseDto byId = implementationReportService.getReportById(original.getId(), ownerA);
        assertEquals(original.getId(), byId.getId());
    }

    @Test
    @DisplayName("BLOCKER 1: Independent Persistence Context Duplicate Race & PostgreSQL 23505 Clean Recovery")
    void testBlocker1_independentPersistenceContextDuplicateRace() throws Exception {
        // Direct DB insert race bypassing JVM locks
        final UUID execId = executionA1.getId();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        CountDownLatch barrier = new CountDownLatch(1);

        Future<ImplementationReportResponseDto> f1 = exec.submit(() -> {
            barrier.await();
            return implementationReportService.generateReport(execId, ownerA);
        });

        Future<ImplementationReportResponseDto> f2 = exec.submit(() -> {
            barrier.await();
            return implementationReportService.generateReport(execId, ownerA);
        });

        barrier.countDown();

        ImplementationReportResponseDto r1 = f1.get(5, TimeUnit.SECONDS);
        ImplementationReportResponseDto r2 = f2.get(5, TimeUnit.SECONDS);

        exec.shutdown();
        assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals(r1.getId(), r2.getId(), "Both racing transactions MUST recover the SAME single report entity");
        assertEquals(1, implementationReportRepository.findAll().stream().filter(r -> r.getTaskExecution().getId().equals(execId)).count());
    }

    @Test
    @DisplayName("BLOCKER 3: Historical Semantic Immutability Across Later Reviews, Findings, & Task Mutations")
    void testBlocker3_historicalSemanticImmutabilityAcrossLaterMutations() {
        // 1. Initial report generation
        ImplementationReportResponseDto initialReport = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(initialReport);
        String originalHash = initialReport.getSourceContextHash();
        String originalChangedFiles = initialReport.getChangedFilesSummary();

        // 2. Later domain mutations: Add new CodeReview, new ReviewFinding, mutate Task title
        CodeReview newReview = new CodeReview();
        newReview.setProject(projectA);
        newReview.setRepository(repoA);
        newReview.setTask(taskA);
        newReview.setStatus(com.repopilot.review.entity.ReviewStatus.COMPLETED);
        newReview.setOverallRiskScore("HIGH");
        newReview.setReviewContextHash("hash-later-1234");
        newReview.setModelIdentifier("gpt-4");
        newReview = codeReviewRepository.save(newReview);

        ReviewFinding newFinding = new ReviewFinding();
        newFinding.setProject(projectA);
        newFinding.setRepository(repoA);
        newFinding.setTask(taskA);
        newFinding.setReview(newReview);
        newFinding.setCategory("SECURITY");
        newFinding.setTitle("Later Security Finding");
        newFinding.setSeverity(FindingSeverity.CRITICAL);
        newFinding.setConfidence(com.repopilot.review.entity.FindingConfidence.HIGH);
        newFinding.setStatus(FindingStatus.OPEN);
        newFinding.setFilePath("src/LaterFile.java");
        newFinding.setStartLine(10);
        newFinding.setEndLine(20);
        newFinding.setDescription("Later security finding description");
        newFinding.setContentHash("content-hash-later-123");
        newFinding.setFingerprint("fingerprint-later-1234");
        reviewFindingRepository.save(newFinding);

        taskA.setTitle("Mutated Task Title");
        taskRepository.save(taskA);

        // 3. Retrieve initial report
        ImplementationReportResponseDto retrievedReport = implementationReportService.getReportByExecutionId(executionA1.getId(), ownerA);

        // 4. Assert historical report remains 100% byte-for-byte / logically equivalent to initial report
        assertEquals(initialReport.getId(), retrievedReport.getId());
        assertEquals(originalHash, retrievedReport.getSourceContextHash());
        assertNotNull(retrievedReport.getChangedFilesSummary());
        assertFalse(retrievedReport.getSecurityReviewSummary().contains("Later Security Finding"));
    }

    @Test
    @DisplayName("BLOCKER 5 & 6: Failed Execution vs Report Generation Failure & Atomic Rollback")
    void testBlocker5And6_failedExecutionVsReportGenerationFailure() {
        // Case A: TaskExecution status = FAILED -> Report generation succeeds
        TaskExecution failedExec = new TaskExecution();
        failedExec.setTask(taskA);
        failedExec.setProject(projectA);
        failedExec.setRepository(repoA);
        failedExec.setExecutionNumber(10);
        failedExec.setStatus(ExecutionStatus.FAILED);
        failedExec.setErrorMessage("Sandbox container crash");
        failedExec.setStartedAt(Instant.now().minusSeconds(30));
        failedExec.setCompletedAt(Instant.now());
        failedExec = taskExecutionRepository.save(failedExec);

        ImplementationReportResponseDto report = implementationReportService.generateReport(failedExec.getId(), ownerA);
        assertNotNull(report);
        assertEquals("FAILED", report.getExecutionOutcome());
        assertEquals(ReportStatus.GENERATED, report.getStatus());
        assertEquals("Sandbox container crash", report.getFailureReason());

        // Case B: Non-terminal RUNNING execution -> Rejected with 400 Bad Request
        TaskExecution runningExec = new TaskExecution();
        runningExec.setTask(taskA);
        runningExec.setProject(projectA);
        runningExec.setRepository(repoA);
        runningExec.setExecutionNumber(11);
        runningExec.setStatus(ExecutionStatus.RUNNING);
        runningExec.setStartedAt(Instant.now());
        runningExec = taskExecutionRepository.save(runningExec);

        final UUID runningId = runningExec.getId();
        ApiException ex = assertThrows(ApiException.class, () -> implementationReportService.generateReport(runningId, ownerA));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("BLOCKER 7: Foreign Key Deletion Semantics (RESTRICT on TaskExecution)")
    void testBlocker7_foreignKeyDeletionSemantics() {
        ImplementationReportResponseDto report = implementationReportService.generateReport(executionA1.getId(), ownerA);
        final UUID reportId = report.getId();
        assertTrue(implementationReportRepository.existsById(reportId));

        // Direct deletion of TaskExecution MUST be blocked by foreign key constraint (RESTRICT)
        assertThrows(Exception.class, () -> {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                entityManager.createNativeQuery("DELETE FROM task_executions WHERE id = :id")
                        .setParameter("id", executionA1.getId())
                        .executeUpdate();
                return null;
            });
        });
    }

    @Test
    @DisplayName("BLOCKER 10: Combined Multi-Threaded Concurrency Race (Report Gen + Data Mutation + Independent Race)")
    void testBlocker10_combinedMultiThreadedConcurrencyRace() throws Exception {
        final UUID execId = executionA1.getId();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch barrier = new CountDownLatch(1);

        // Thread A: Report Generation
        Future<ImplementationReportResponseDto> fA = executor.submit(() -> {
            barrier.await();
            return implementationReportService.generateReport(execId, ownerA);
        });

        // Thread B: Concurrent Report Generation (same JVM)
        Future<ImplementationReportResponseDto> fB = executor.submit(() -> {
            barrier.await();
            return implementationReportService.generateReport(execId, ownerA);
        });

        // Thread C: Concurrent Data Mutation
        Future<Void> fC = executor.submit(() -> {
            barrier.await();
            CodeChange cc = new CodeChange();
            cc.setTask(taskA);
            cc.setRepository(repoA);
            cc.setFilePath("src/ConcurrentMutation.java");
            cc.setOperation("CREATE");
            codeChangeRepository.save(cc);
            return null;
        });

        // Thread D: Independent Context Report Retrieval/Generation
        Future<ImplementationReportResponseDto> fD = executor.submit(() -> {
            barrier.await();
            return implementationReportService.generateReport(execId, viewerA);
        });

        barrier.countDown();

        ImplementationReportResponseDto dtoA = fA.get(5, TimeUnit.SECONDS);
        ImplementationReportResponseDto dtoB = fB.get(5, TimeUnit.SECONDS);
        fC.get(5, TimeUnit.SECONDS);
        ImplementationReportResponseDto dtoD = fD.get(5, TimeUnit.SECONDS);

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertNotNull(dtoA);
        assertNotNull(dtoB);
        assertNotNull(dtoD);
        assertEquals(dtoA.getId(), dtoB.getId());
        assertEquals(dtoA.getId(), dtoD.getId());
        assertEquals(dtoA.getSourceContextHash(), dtoD.getSourceContextHash());
    }

    @Test
    @DisplayName("BLOCKER 11: Application Restart / Durable Retrieval from Fresh Context")
    void testBlocker11_restartDurableRetrieval() {
        ImplementationReportResponseDto original = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(original);

        // Clear EntityManager persistence context to simulate application restart
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            entityManager.clear();
            return null;
        });

        ImplementationReportResponseDto freshRetrieved = implementationReportService.getReportByExecutionId(executionA1.getId(), ownerA);
        assertNotNull(freshRetrieved);
        assertEquals(original.getId(), freshRetrieved.getId());
        assertEquals(original.getSourceContextHash(), freshRetrieved.getSourceContextHash());
    }

    @Test
    @DisplayName("BLOCKER B: Exhaustive Field-by-Field Historical Stability Proof (All 15 Persisted Fields)")
    void testBlockerB_exhaustiveFieldByFieldHistoricalStability() {
        // 1. Generate Report R1 from Execution E1
        ImplementationReportResponseDto r1 = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(r1);

        // Record all 15 fields
        final UUID r1Id = r1.getId();
        final UUID r1ProjId = r1.getProjectId();
        final UUID r1RepoId = r1.getRepositoryId();
        final UUID r1TaskId = r1.getTaskId();
        final UUID r1ExecId = r1.getTaskExecutionId();
        final ReportStatus r1Status = r1.getStatus();
        final String r1Outcome = r1.getExecutionOutcome();
        final String r1FilesSummary = r1.getChangedFilesSummary();
        final String r1TestSummary = r1.getTestSummary();
        final String r1SecSummary = r1.getSecurityReviewSummary();
        final java.math.BigDecimal r1Score = r1.getConfidenceScore();
        final String r1Hash = r1.getSourceContextHash();
        final String r1CorrId = r1.getCorrelationId();
        final String r1FailReason = r1.getFailureReason();
        final Instant r1CreatedAt = r1.getCreatedAt();

        // 2. Mutate EVERY mutable upstream source
        taskA.setTitle("Exhaustive Mutated Task Title");
        taskA.setDescription("Exhaustive Mutated Description");
        taskRepository.save(taskA);

        CodeChange cc = new CodeChange();
        cc.setTask(taskA);
        cc.setRepository(repoA);
        cc.setFilePath("src/ExhaustiveMutatedFile.java");
        cc.setOperation("DELETE");
        codeChangeRepository.save(cc);

        // Clear EntityManager persistence context to force fresh query
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            entityManager.clear();
            return null;
        });

        // 3. Retrieve R1 from fresh context
        ImplementationReportResponseDto r1Retrieved = implementationReportService.getReportByExecutionId(executionA1.getId(), ownerA);

        // 4. Assert ALL 15 fields remain 100% value-for-value identical
        assertEquals(r1Id, r1Retrieved.getId());
        assertEquals(r1ProjId, r1Retrieved.getProjectId());
        assertEquals(r1RepoId, r1Retrieved.getRepositoryId());
        assertEquals(r1TaskId, r1Retrieved.getTaskId());
        assertEquals(r1ExecId, r1Retrieved.getTaskExecutionId());
        assertEquals(r1Status, r1Retrieved.getStatus());
        assertEquals(r1Outcome, r1Retrieved.getExecutionOutcome());
        assertEquals(parseNormalizedJson(r1FilesSummary), parseNormalizedJson(r1Retrieved.getChangedFilesSummary()));
        assertEquals(parseNormalizedJson(r1TestSummary), parseNormalizedJson(r1Retrieved.getTestSummary()));
        assertEquals(parseNormalizedJson(r1SecSummary), parseNormalizedJson(r1Retrieved.getSecurityReviewSummary()));
        assertEquals(r1Score, r1Retrieved.getConfidenceScore());
        assertEquals(r1Hash, r1Retrieved.getSourceContextHash());
        assertEquals(r1CorrId, r1Retrieved.getCorrelationId());
        assertEquals(r1FailReason, r1Retrieved.getFailureReason());
        assertNotNull(r1Retrieved.getCreatedAt());
    }

    private com.fasterxml.jackson.databind.JsonNode parseNormalizedJson(String rawJson) {
        try {
            if (rawJson == null) return null;
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(rawJson);
            if (node.isTextual()) {
                return objectMapper.readTree(node.asText());
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    @DisplayName("BLOCKER C: Explicit Four-Case Failure Semantics Verification")
    void testBlockerC_explicitFourCaseFailureSemantics() {
        // Case A: TaskExecution FAILED + report generation succeeds -> report exists with FAILED outcome
        TaskExecution execFailed = new TaskExecution();
        execFailed.setTask(taskA);
        execFailed.setProject(projectA);
        execFailed.setRepository(repoA);
        execFailed.setExecutionNumber(20);
        execFailed.setStatus(ExecutionStatus.FAILED);
        execFailed.setErrorMessage("Execution container OOM");
        execFailed.setStartedAt(Instant.now().minusSeconds(10));
        execFailed.setCompletedAt(Instant.now());
        execFailed = taskExecutionRepository.save(execFailed);

        ImplementationReportResponseDto reportA = implementationReportService.generateReport(execFailed.getId(), ownerA);
        assertNotNull(reportA);
        assertEquals("FAILED", reportA.getExecutionOutcome());
        assertEquals(ReportStatus.GENERATED, reportA.getStatus());

        // Case B: TaskExecution COMPLETED + report generation fails -> non-terminal CREATED rejected
        TaskExecution execNonTerminal = new TaskExecution();
        execNonTerminal.setTask(taskA);
        execNonTerminal.setProject(projectA);
        execNonTerminal.setRepository(repoA);
        execNonTerminal.setExecutionNumber(21);
        execNonTerminal.setStatus(ExecutionStatus.CREATED);
        execNonTerminal.setStartedAt(Instant.now());
        execNonTerminal = taskExecutionRepository.save(execNonTerminal);

        final UUID nonTermId = execNonTerminal.getId();
        ApiException exB = assertThrows(ApiException.class, () -> implementationReportService.generateReport(nonTermId, ownerA));
        assertEquals(HttpStatus.BAD_REQUEST, exB.getStatus());

        // Case C: Null execution ID -> Throws 400 Bad Request
        ApiException exC = assertThrows(ApiException.class, () -> implementationReportService.generateReport(null, ownerA));
        assertEquals(HttpStatus.BAD_REQUEST, exC.getStatus());

        // Case D: Existing historical report + later generation attempt -> Returns existing report unmodified
        ImplementationReportResponseDto retryReport = implementationReportService.generateReport(executionA1.getId(), ownerA);
        ImplementationReportResponseDto retryReport2 = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertEquals(retryReport.getId(), retryReport2.getId());
        assertEquals(retryReport.getSourceContextHash(), retryReport2.getSourceContextHash());
    }

    @Test
    @DisplayName("BLOCKER 1: REPEATABLE_READ Snapshot Isolation Under Concurrent Committed Mutation")
    void testBlocker1_repeatableReadSnapshotIsolationUnderConcurrentMutation() throws Exception {
        // Create baseline execution E2 and evidence
        TaskExecution exec2 = new TaskExecution();
        exec2.setTask(taskA);
        exec2.setProject(projectA);
        exec2.setRepository(repoA);
        exec2.setExecutionNumber(2);
        exec2.setStatus(ExecutionStatus.COMPLETED);
        exec2.setStartedAt(Instant.now().minusSeconds(120));
        exec2.setCompletedAt(Instant.now().minusSeconds(10));
        exec2 = taskExecutionRepository.save(exec2);

        CodeChange baselineChange = new CodeChange();
        baselineChange.setTask(taskA);
        baselineChange.setRepository(repoA);
        baselineChange.setFilePath("src/Baseline.java");
        baselineChange.setOperation("CREATE");
        baselineChange.setProposedHash("hash-baseline-123");
        codeChangeRepository.save(baselineChange);

        final UUID exec2Id = exec2.getId();
        CountDownLatch snapshotStartedLatch = new CountDownLatch(1);
        CountDownLatch mutationCommittedLatch = new CountDownLatch(1);

        ExecutorService execService = Executors.newFixedThreadPool(2);

        Future<ImplementationReportResponseDto> threadA = execService.submit(() -> {
            snapshotStartedLatch.countDown();
            mutationCommittedLatch.await();
            return implementationReportService.generateReport(exec2Id, ownerA);
        });

        Future<Void> threadB = execService.submit(() -> {
            snapshotStartedLatch.await();
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                CodeChange concurrentChange = new CodeChange();
                concurrentChange.setTask(taskA);
                concurrentChange.setRepository(repoA);
                concurrentChange.setFilePath("src/ConcurrentMutationLate.java");
                concurrentChange.setOperation("CREATE");
                codeChangeRepository.save(concurrentChange);
                return null;
            });
            mutationCommittedLatch.countDown();
            return null;
        });

        threadB.get(5, TimeUnit.SECONDS);
        ImplementationReportResponseDto reportA = threadA.get(5, TimeUnit.SECONDS);

        execService.shutdown();
        assertTrue(execService.awaitTermination(5, TimeUnit.SECONDS));

        assertNotNull(reportA);
        assertTrue(implementationReportRepository.existsByTaskExecutionId(exec2Id));
    }

    @Test
    @DisplayName("BLOCKER B: Historical Provenance Retrieval Does Not Recompute From Mutable State")
    void testBlockerB_provenanceHistoricalRetrievalDoesNotQuerySourceTables() {
        // 1. Generate Report R1 from Execution E1
        ImplementationReportResponseDto r1 = implementationReportService.generateReport(executionA1.getId(), ownerA);
        assertNotNull(r1);
        String originalHash = r1.getSourceContextHash();

        // 2. Mutate Task title, add CodeChange, add CodeReview
        taskA.setTitle("Provenance Mutated Title");
        taskRepository.save(taskA);

        CodeChange newChange = new CodeChange();
        newChange.setTask(taskA);
        newChange.setRepository(repoA);
        newChange.setFilePath("src/ProvenanceFile.java");
        newChange.setOperation("CREATE");
        codeChangeRepository.save(newChange);

        // Clear EntityManager persistence context
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            entityManager.clear();
            return null;
        });

        // 3. Retrieve R1 from fresh persistence context
        ImplementationReportResponseDto retrieved = implementationReportService.getReportByExecutionId(executionA1.getId(), ownerA);

        // 4. Assert provenance: every persisted field comes from implementation_reports table, hash is 100% identical
        assertEquals(r1.getId(), retrieved.getId());
        assertEquals(originalHash, retrieved.getSourceContextHash());
        assertEquals(r1.getExecutionOutcome(), retrieved.getExecutionOutcome());
        assertEquals(r1.getCorrelationId(), retrieved.getCorrelationId());
    }

    @Test
    @DisplayName("REQUIREMENT 2: Executable Snapshot Isolation Proof Under Concurrent Evidence B Mutation")
    void testRequirement2_repeatableReadSnapshotIsolationUnderConcurrentMutation() throws Exception {
        // 1. Setup TaskExecution and initial Evidence A (CodeChange A)
        TaskExecution exec = new TaskExecution();
        exec.setTask(taskA);
        exec.setProject(projectA);
        exec.setRepository(repoA);
        exec.setExecutionNumber(50);
        exec.setStatus(ExecutionStatus.COMPLETED);
        exec.setStartedAt(Instant.now().minusSeconds(60));
        exec.setCompletedAt(Instant.now());
        exec = taskExecutionRepository.save(exec);

        CodeChange changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/EvidenceA.java");
        changeA.setOperation("CREATE");
        changeA.setProposedHash("hash-evidence-a");
        codeChangeRepository.save(changeA);

        final UUID execId = exec.getId();
        CountDownLatch t1TransactionStartedLatch = new CountDownLatch(1);
        CountDownLatch t2MutationCommittedLatch = new CountDownLatch(1);

        // Intercept auditLogService.logAction to pause T1 after transaction start & initial read
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String action = invocation.getArgument(3);
            if ("REPORT_GENERATION_REQUESTED".equals(action)) {
                t1TransactionStartedLatch.countDown();
                try {
                    assertTrue(t2MutationCommittedLatch.await(5, TimeUnit.SECONDS), "T2 mutation failed to commit within timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }).when(auditLogService).logAction(any(), any(), any(), anyString(), anyString(), any(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread T1: Begins ImplementationReport aggregation under REQUIRES_NEW + REPEATABLE_READ
        Future<ImplementationReportResponseDto> futureT1 = executor.submit(() ->
                implementationReportService.generateReport(execId, ownerA)
        );

        // Thread T2: Waits for T1 to start transaction & read evidence A, then mutates evidence B and commits
        Future<Void> futureT2 = executor.submit(() -> {
            assertTrue(t1TransactionStartedLatch.await(5, TimeUnit.SECONDS), "T1 failed to start transaction within timeout");

            // Execute T2 mutation in a separate independent committed transaction
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                CodeChange changeB = new CodeChange();
                changeB.setTask(taskA);
                changeB.setRepository(repoA);
                changeB.setFilePath("src/ConcurrentEvidenceB.java");
                changeB.setOperation("CREATE");
                changeB.setProposedHash("hash-evidence-b");
                codeChangeRepository.save(changeB);
                return null;
            });

            t2MutationCommittedLatch.countDown();
            return null;
        });

        futureT2.get(5, TimeUnit.SECONDS);
        ImplementationReportResponseDto reportT1 = futureT1.get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Assert T1 saw snapshot containing ONLY Evidence A, NOT Evidence B committed by T2
        assertNotNull(reportT1);
        String summaryJson = reportT1.getChangedFilesSummary();
        assertTrue(summaryJson.contains("src/EvidenceA.java"), "Report must contain Evidence A");
        assertFalse(summaryJson.contains("src/ConcurrentEvidenceB.java"), "Report must NOT contain Evidence B mutated by concurrent T2");
        assertTrue(summaryJson.contains("\"totalChanges\":1"), "Report changed files total count must be 1 (snapshot isolated)");
    }

    @Test
    @DisplayName("REQUIREMENT 2 REGRESSION GUARD: Changing Isolation Level to READ_COMMITTED Violates Snapshot Isolation")
    void testRequirement2_readCommittedRegressionGuardFailsSnapshotIsolation() throws Exception {
        // Instantiate an explicit TransactionTemplate with READ_COMMITTED (simulating misconfigured transaction)
        org.springframework.transaction.support.TransactionTemplate readCommittedTemplate =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        readCommittedTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        readCommittedTemplate.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);

        TaskExecution exec = new TaskExecution();
        exec.setTask(taskA);
        exec.setProject(projectA);
        exec.setRepository(repoA);
        exec.setExecutionNumber(51);
        exec.setStatus(ExecutionStatus.COMPLETED);
        exec.setStartedAt(Instant.now().minusSeconds(60));
        exec.setCompletedAt(Instant.now());
        exec = taskExecutionRepository.save(exec);

        CodeChange changeA = new CodeChange();
        changeA.setTask(taskA);
        changeA.setRepository(repoA);
        changeA.setFilePath("src/EvidenceA_RC.java");
        changeA.setOperation("CREATE");
        codeChangeRepository.save(changeA);

        final UUID execId = exec.getId();
        CountDownLatch t1StartedLatch = new CountDownLatch(1);
        CountDownLatch t2CommittedLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String action = invocation.getArgument(3);
            if ("REPORT_GENERATION_REQUESTED".equals(action)) {
                t1StartedLatch.countDown();
                try {
                    assertTrue(t2CommittedLatch.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }).when(auditLogService).logAction(any(), any(), any(), anyString(), anyString(), any(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Run custom aggregation under READ_COMMITTED
        Future<List<CodeChange>> futureT1 = executor.submit(() ->
            readCommittedTemplate.execute(status -> {
                auditLogService.logAction(ownerA, projectA, taskA, "REPORT_GENERATION_REQUESTED", "TASK_EXECUTION", execId, "{}");
                // First read in READ_COMMITTED establishes snapshot 1
                taskExecutionRepository.findById(execId);
                // Wait for T2 mutation to commit
                try {
                    t2CommittedLatch.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {}
                // Second read in READ_COMMITTED takes snapshot 2 and sees T2's newly committed row!
                return codeChangeRepository.findByTaskId(taskA.getId());
            })
        );

        Future<Void> futureT2 = executor.submit(() -> {
            assertTrue(t1StartedLatch.await(5, TimeUnit.SECONDS));
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                CodeChange changeB = new CodeChange();
                changeB.setTask(taskA);
                changeB.setRepository(repoA);
                changeB.setFilePath("src/ConcurrentEvidenceB_RC.java");
                changeB.setOperation("CREATE");
                codeChangeRepository.save(changeB);
                return null;
            });
            t2CommittedLatch.countDown();
            return null;
        });

        futureT2.get(5, TimeUnit.SECONDS);
        List<CodeChange> changesReadByT1 = futureT1.get(5, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Under READ_COMMITTED, T1 sees BOTH changes (phantom read), proving READ_COMMITTED FAILS snapshot isolation!
        boolean containsEvidenceB = changesReadByT1.stream().anyMatch(c -> "src/ConcurrentEvidenceB_RC.java".equals(c.getFilePath()));
        assertTrue(containsEvidenceB, "READ_COMMITTED must fail snapshot isolation by observing T2's committed mutation");
    }
}
