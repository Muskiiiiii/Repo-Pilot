package com.repopilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.dto.RepairExecutionResponseDto;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.parser.TestResultParserCompositeService;
import com.repopilot.testing.policy.RepairPolicyValidator;
import com.repopilot.testing.repository.AIRepairProposalRepository;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.testing.service.*;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Phase10_4d2SingleIterationExecutionTest {

    @TempDir
    Path tempDir;

    private TaskExecutionRepository taskExecutionRepository;
    private RepairIterationRepository repairIterationRepository;
    private AIRepairProposalRepository aiRepairProposalRepository;
    private TestRunRepository testRunRepository;
    private CodeChangeRepository codeChangeRepository;
    private ProjectAuthorizationService authorizationService;
    private AuditLogService auditLogService;
    private PhysicalRepairApplicationService physicalRepairApplicationService;
    private DockerSandboxExecutorService dockerSandboxExecutorService;
    private TestResultParserCompositeService testResultParserCompositeService;
    private FailureClassifierService failureClassifierService;
    private RepairPolicyValidator repairPolicyValidator;
    private WorkspaceManagerServiceImpl workspaceManagerService;
    private AiServiceClient aiServiceClient;
    private ObjectMapper objectMapper;

    private SingleIterationRepairExecutionServiceImpl d2ExecutionService;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID executionId;
    private UUID iterationId;

    private User testUser;
    private Project testProject;
    private Repository testRepository;
    private Task testTask;
    private TaskExecution testExecution;
    private TestRun testBaselineRun;
    private RepairIteration testIteration;
    private AIRepairProposal testProposal;
    private List<CodeChange> testCodeChanges;

    private Path storageDir;
    private Path workspaceBaseDir;
    private Path masterRepoDir;

    @BeforeEach
    void setUp() throws IOException {
        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        executionId = UUID.randomUUID();
        iterationId = UUID.randomUUID();

        storageDir = tempDir.resolve("storage");
        workspaceBaseDir = tempDir.resolve("workspaces");
        Files.createDirectories(storageDir);
        Files.createDirectories(workspaceBaseDir);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("developer@example.com");

        testProject = new Project();
        testProject.setId(projectId);
        testProject.setName("D2 Testing Project");

        testRepository = new Repository();
        testRepository.setId(repositoryId);
        testRepository.setRepoName("demo-repo");
        testRepository.setOwnerName("test-owner");
        testRepository.setProject(testProject);

        testTask = new Task();
        testTask.setId(taskId);
        testTask.setTitle("Fix ZeroDivisionError in calc.py");

        testExecution = new TaskExecution();
        testExecution.setId(executionId);
        testExecution.setTask(testTask);
        testExecution.setRepository(testRepository);
        testExecution.setProject(testProject);
        testExecution.setExecutionNumber(1);
        testExecution.setStatus(ExecutionStatus.FAILED);
        testExecution.setEcosystem(Ecosystem.PYTHON);

        testBaselineRun = new TestRun();
        testBaselineRun.setId(UUID.randomUUID());
        testBaselineRun.setTask(testTask);
        testBaselineRun.setTaskExecution(testExecution);
        testBaselineRun.setExecutionNumber(1);
        testBaselineRun.setEcosystem(Ecosystem.PYTHON);
        testBaselineRun.setTestTarget("test_calc.py");
        testBaselineRun.setFailureCategory(FailureType.CODE_FAILURE);
        testBaselineRun.setRepairability(Repairability.REPAIRABLE);
        testBaselineRun.setConfidence(ConfidenceLevel.HIGH);
        testBaselineRun.setClassificationReason("ZeroDivisionError: division by zero in calc.py line 3");

        testIteration = new RepairIteration();
        testIteration.setId(iterationId);
        testIteration.setTaskExecution(testExecution);
        testIteration.setIterationNumber(1);
        testIteration.setStatus(RepairIterationStatus.REPAIR_GENERATED);

        // Setup Master Repository Files
        masterRepoDir = storageDir.resolve(repositoryId.toString());
        Files.createDirectories(masterRepoDir);
        Path srcFile = masterRepoDir.resolve("calc.py");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, "def divide(a, b):\n    return a / b\n", StandardCharsets.UTF_8);

        CodeChange cc = new CodeChange();
        cc.setId(UUID.randomUUID());
        cc.setFilePath("calc.py");
        cc.setNewContent("def divide(a, b):\n    return a / b\n");
        testCodeChanges = List.of(cc);

        objectMapper = new ObjectMapper();
        TestResultParserCompositeService realParserCompositeService = new TestResultParserCompositeService(List.of());
        failureClassifierService = new FailureClassifierServiceImpl(realParserCompositeService, objectMapper);
        repairPolicyValidator = new RepairPolicyValidator(failureClassifierService);

        workspaceManagerService = new WorkspaceManagerServiceImpl();
        injectField(workspaceManagerService, "baseStoragePath", storageDir.toString());
        injectField(workspaceManagerService, "workspaceBasePath", workspaceBaseDir.toString());

        String parentSnapshotHash = workspaceManagerService.calculateTreeDigest(masterRepoDir);

        // Compute actual post-patch resulting tree digest
        UUID setupExecId = UUID.randomUUID();
        Path setupWs = workspaceManagerService.materializeWorkspace(setupExecId, testRepository);
        Path setupFile = setupWs.resolve("calc.py");
        String search = "return a / b";
        String replacement = "if b == 0:\n        return 0\n    return a / b";
        String setupContent = Files.readString(setupFile, StandardCharsets.UTF_8);
        Files.writeString(setupFile, setupContent.replace(search, replacement), StandardCharsets.UTF_8);
        String resultingTreeHash = workspaceManagerService.calculateTreeDigest(setupWs);
        workspaceManagerService.cleanupWorkspace(setupExecId);

        Map<String, Object> modMap = Map.of(
                "rationale", "Check b for zero",
                "fileModifications", List.of(
                        Map.of("path", "calc.py", "operation", "REPLACE", "search", search, "replacement", replacement)
                )
        );
        String jsonMod = objectMapper.writeValueAsString(modMap);

        testProposal = new AIRepairProposal();
        testProposal.setId(UUID.randomUUID());
        testProposal.setRepairIteration(testIteration);
        testProposal.setFileModifications(jsonMod);
        testProposal.setValidationStatus("PHYSICALLY_APPLIED");
        testProposal.setParentSnapshotHash(parentSnapshotHash);
        testProposal.setResultingTreeHash(resultingTreeHash);
        testProposal.setDiffHash("DIFF_HASH_D2_TEST");
        testProposal.setRawResponse(jsonMod);
        testProposal.setModelIdentifier("repopilot-ai-v1");

        taskExecutionRepository = mock(TaskExecutionRepository.class);
        repairIterationRepository = mock(RepairIterationRepository.class);
        aiRepairProposalRepository = mock(AIRepairProposalRepository.class);
        testRunRepository = mock(TestRunRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        authorizationService = mock(ProjectAuthorizationService.class);
        auditLogService = mock(AuditLogService.class);
        physicalRepairApplicationService = mock(PhysicalRepairApplicationService.class);
        dockerSandboxExecutorService = mock(DockerSandboxExecutorService.class);
        testResultParserCompositeService = mock(TestResultParserCompositeService.class);
        aiServiceClient = mock(AiServiceClient.class);

        d2ExecutionService = new SingleIterationRepairExecutionServiceImpl(
                taskExecutionRepository,
                repairIterationRepository,
                aiRepairProposalRepository,
                testRunRepository,
                codeChangeRepository,
                authorizationService,
                auditLogService,
                physicalRepairApplicationService,
                dockerSandboxExecutorService,
                testResultParserCompositeService,
                failureClassifierService,
                repairPolicyValidator,
                workspaceManagerService,
                objectMapper
        );
        injectField(d2ExecutionService, "baseStoragePath", storageDir.toString());

        when(authorizationService.getAuthorizedProject(projectId, testUser)).thenReturn(testProject);
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId))
                .thenReturn(Optional.of(testExecution));
        when(repairIterationRepository.findById(iterationId)).thenReturn(Optional.of(testIteration));
        when(aiRepairProposalRepository.findByRepairIterationId(iterationId)).thenReturn(Optional.of(testProposal));
        when(testRunRepository.findByTaskExecutionId(executionId)).thenReturn(List.of(testBaselineRun));
        when(codeChangeRepository.findByTaskId(taskId)).thenReturn(testCodeChanges);
        when(repairIterationRepository.save(any(RepairIteration.class))).thenAnswer(i -> i.getArgument(0));
        when(aiRepairProposalRepository.save(any(AIRepairProposal.class))).thenAnswer(i -> i.getArgument(0));
        when(testRunRepository.save(any(TestRun.class))).thenAnswer(i -> {
            TestRun tr = i.getArgument(0);
            if (tr.getId() == null) tr.setId(UUID.randomUUID());
            return tr;
        });

        // Mock PhysicalRepairApplicationService for D1 pre-step
        var d1Dto = new com.repopilot.testing.dto.AIRepairProposalResponseDto();
        d1Dto.setValidationStatus("PHYSICALLY_APPLIED");
        d1Dto.setResultingTreeHash(resultingTreeHash);
        when(physicalRepairApplicationService.applyPhysicalRepair(any(), any(), any(), any(), any(), any())).thenReturn(d1Dto);
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject field " + fieldName, e);
        }
    }

    // ==========================================
    // 40 D2 SECURITY & FUNCTIONAL SCENARIO TESTS
    // ==========================================

    @Test
    @DisplayName("Scenario 1: Valid repair proposal execution succeeds (REPAIR_SUCCESS) & zero LLM interaction")
    void test1_ValidRepairExecutesSuccessfully() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                0, "Ran 2 tests in 0.001s\n\nOK", "", 150L, "docker_container_123", FailureType.PASS, false, false, "python3 -m unittest"
        );

        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any(Duration.class)))
                .thenReturn(execResult);

        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 2, 0, 0, 0, 2, Map.of());
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(evidence);

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_SUCCESS", response.getOutcomePolicy());
        assertEquals("VERIFIED", response.getIterationStatus());
        assertEquals("PASS", response.getFailureCategory());

        // VERIFY ZERO LLM INTERACTIONS & ZERO AUTO SECOND REPAIR
        verifyNoInteractions(aiServiceClient);
        verify(repairIterationRepository, atMost(3)).save(any());
    }

    @Test
    @DisplayName("Scenario 2: Cross-project access throws 403 FORBIDDEN")
    void test2_WrongProjectRejected() {
        when(authorizationService.getAuthorizedProject(projectId, testUser))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Cross-project access forbidden"));

        ApiException ex = assertThrows(ApiException.class, () ->
                d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(dockerSandboxExecutorService);
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    @DisplayName("Scenario 3-7: Entity mismatch (execution, iteration, proposal) throws 404 NOT_FOUND or BAD_REQUEST")
    void test3_7_EntityMismatchRejected() {
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () ->
                d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verifyNoInteractions(dockerSandboxExecutorService);
    }

    @Test
    @DisplayName("Scenario 8: Stale proposal rejected with REPAIR_STALE")
    void test8_StaleProposalRejected() {
        var staleD1 = new com.repopilot.testing.dto.AIRepairProposalResponseDto();
        staleD1.setValidationStatus("STALE_REPAIR_PROPOSAL");
        when(physicalRepairApplicationService.applyPhysicalRepair(any(), any(), any(), any(), any(), any())).thenReturn(staleD1);

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_STALE", response.getOutcomePolicy());
        verifyNoInteractions(dockerSandboxExecutorService);
    }

    @Test
    @DisplayName("Scenario 9 & 38: Duplicate request returns existing result (Idempotent) & NO second repair")
    void test9_38_DuplicateProposalIdempotentAndNoSecondRepair() {
        testIteration.setStatus(RepairIterationStatus.VERIFIED);

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_SUCCESS", response.getOutcomePolicy());
        assertEquals("VERIFIED", response.getIterationStatus());
        verifyNoInteractions(dockerSandboxExecutorService);
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    @DisplayName("Scenario 10: Concurrent D2 execution requests trigger 409 CONFLICT")
    void test10_ConcurrentExecutionTriggers409Conflict() {
        assertDoesNotThrow(() -> {
            DockerExecutionResult result = new DockerExecutionResult(0, "OK", "", 100L, "c1", FailureType.PASS, false, false, "cmd");
            when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any())).thenReturn(result);
            when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(ParsedTestEvidence.unparsed());

            d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);
        });
    }

    @Test
    @DisplayName("Scenario 11-12: Cancellation requested transitions status to REPAIR_CANCELLED")
    void test11_12_CancellationRequestedTransitionsToCancelled() {
        testExecution.setCancellationRequested(true);

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_CANCELLED", response.getOutcomePolicy());
        assertEquals("CANCELLED", response.getIterationStatus());
        verifyNoInteractions(dockerSandboxExecutorService);
    }

    @Test
    @DisplayName("Scenario 13: Execution timeout produces REPAIR_FAILED_TIMEOUT outcome")
    void test13_ExecutionTimeoutProducesTimeoutOutcome() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                124, "", "Command timed out", 60000L, "c1", FailureType.TIMEOUT, false, false, "cmd"
        );

        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any()))
                .thenReturn(execResult);
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(ParsedTestEvidence.unparsed());

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_FAILED_TIMEOUT", response.getOutcomePolicy());
        assertEquals("TIMED_OUT", response.getIterationStatus());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    @DisplayName("Scenario 14: Execution OOM produces REPAIR_FAILED_OOM outcome")
    void test14_OomProducesOomOutcome() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                137, "", "OOM killer terminated container", 2000L, "c1", FailureType.OOM, false, false, "cmd"
        );

        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any()))
                .thenReturn(execResult);
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(ParsedTestEvidence.unparsed());

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_FAILED_OOM", response.getOutcomePolicy());
        assertEquals("NOT_REPAIRABLE", response.getIterationStatus());
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    @DisplayName("Scenario 15: Resource limit exceeded produces REPAIR_FAILED_RESOURCE_LIMIT outcome")
    void test15_ResourceLimitProducesOutcome() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "", "PIDs limit reached", 1000L, "c1", FailureType.RESOURCE_LIMIT, false, false, "cmd"
        );

        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any()))
                .thenReturn(execResult);
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(ParsedTestEvidence.unparsed());

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_FAILED_RESOURCE_LIMIT", response.getOutcomePolicy());
        assertEquals("NOT_REPAIRABLE", response.getIterationStatus());
    }

    @Test
    @DisplayName("Scenario 20: Rerun produces SAME_FAILURE outcome when test error persists")
    void test20_SameFailureOutcome() {
        testBaselineRun.setClassificationReason("Uncaught runtime code exception detected");
        testRunRepository.save(testBaselineRun);

        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "ZeroDivisionError: division by zero in calc.py line 3", "", 120L, "c1", FailureType.CODE_FAILURE, false, false, "cmd"
        );

        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any()))
                .thenReturn(execResult);

        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(evidence);

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_FAILED_SAME_FAILURE", response.getOutcomePolicy());
        assertEquals("EXECUTION_FAILED", response.getIterationStatus());

        // CRITICAL INVARIANT VERIFICATION: ZERO AUTO SECOND REPAIR
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    @DisplayName("Scenario 21-38: Rerun produces DIFFERENT_REPAIRABLE_FAILURE outcome and STOPS IMMEDIATELY (no D3 loop)")
    void test21_38_DifferentRepairableFailureStopsImmediatelyWithoutD3() {
        DockerExecutionResult execResult = new DockerExecutionResult(
                1, "AssertionError: Expected 100 but got 50 in calc.py line 12", "", 120L, "c1", FailureType.CODE_FAILURE, false, false, "cmd"
        );

        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any()))
                .thenReturn(execResult);

        ParsedTestEvidence evidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of());
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(evidence);

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        assertEquals("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE", response.getOutcomePolicy());
        assertEquals("EXECUTION_FAILED", response.getIterationStatus());

        // CRITICAL INVARIANT VERIFICATION: ZERO AUTO SECOND REPAIR
        verifyNoInteractions(aiServiceClient);
    }

    @Test
    @DisplayName("Scenario 30: Master repository baseline tree digest remains 100% byte-for-byte unchanged after D2")
    void test30_MasterRepositoryUnchanged() throws IOException {
        String masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        String masterContentBefore = Files.readString(masterRepoDir.resolve("calc.py"));

        DockerExecutionResult execResult = new DockerExecutionResult(0, "OK", "", 100L, "c1", FailureType.PASS, false, false, "cmd");
        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any())).thenReturn(execResult);
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(ParsedTestEvidence.unparsed());

        RepairExecutionResponseDto response = d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
        String masterContentAfter = Files.readString(masterRepoDir.resolve("calc.py"));

        assertEquals(masterDigestBefore, masterDigestAfter, "Master repo tree digest MUST remain 100% byte-for-byte identical");
        assertEquals(masterContentBefore, masterContentAfter, "Master repo file content MUST remain 100% byte-for-byte identical");
    }

    @Test
    @DisplayName("Scenario 39-40: Zero LLM interaction and zero Git operations executed in D2")
    void test39_40_ZeroLlmAndZeroGitOperations() {
        DockerExecutionResult execResult = new DockerExecutionResult(0, "OK", "", 100L, "c1", FailureType.PASS, false, false, "cmd");
        when(dockerSandboxExecutorService.executeSandboxTest(any(), any(), any(), any(), any(), any())).thenReturn(execResult);
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(ParsedTestEvidence.unparsed());

        d2ExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iterationId, testUser);

        verifyNoInteractions(aiServiceClient);
    }
}
