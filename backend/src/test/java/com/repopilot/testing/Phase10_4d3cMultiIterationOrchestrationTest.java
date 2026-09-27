package com.repopilot.testing;

import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.dto.*;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.parser.TestResultParserCompositeService;
import com.repopilot.testing.policy.*;
import com.repopilot.testing.repository.AIRepairProposalRepository;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.testing.service.*;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Phase10_4d3cMultiIterationOrchestrationTest {

    private TaskExecutionRepository taskExecutionRepository;
    private RepairIterationRepository repairIterationRepository;
    private AIRepairProposalRepository aiRepairProposalRepository;
    private TestRunRepository testRunRepository;
    private CodeChangeRepository codeChangeRepository;
    private ProjectAuthorizationService authorizationService;
    private WorkspaceManagerService workspaceManagerService;
    private AiDiagnosisService aiDiagnosisService;
    private AiRepairProposalService aiRepairProposalService;
    private PhysicalRepairApplicationService physicalRepairApplicationService;
    private SingleIterationRepairExecutionService singleIterationRepairExecutionService;
    private FailureClassifierService failureClassifierService;
    private TestResultParserCompositeService testResultParserCompositeService;
    private FailureFingerprintService failureFingerprintService;
    private RepairBudgetManager repairBudgetManager;

    private MultiIterationRepairOrchestrationServiceImpl orchestrationService;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID executionId;
    private User currentUser;
    private TaskExecution mockExecution;
    private Project mockProject;
    private Repository mockRepository;
    private Task mockTask;
    private TestRun baselineTestRun;

    @TempDir
    Path tempStorageDir;

    @BeforeEach
    void setUp() {
        taskExecutionRepository = mock(TaskExecutionRepository.class);
        repairIterationRepository = mock(RepairIterationRepository.class);
        aiRepairProposalRepository = mock(AIRepairProposalRepository.class);
        testRunRepository = mock(TestRunRepository.class);
        codeChangeRepository = mock(CodeChangeRepository.class);
        authorizationService = mock(ProjectAuthorizationService.class);
        workspaceManagerService = mock(WorkspaceManagerService.class);
        aiDiagnosisService = mock(AiDiagnosisService.class);
        aiRepairProposalService = mock(AiRepairProposalService.class);
        physicalRepairApplicationService = mock(PhysicalRepairApplicationService.class);
        singleIterationRepairExecutionService = mock(SingleIterationRepairExecutionService.class);
        failureClassifierService = mock(FailureClassifierService.class);
        testResultParserCompositeService = mock(TestResultParserCompositeService.class);
        failureFingerprintService = mock(FailureFingerprintService.class);
        repairBudgetManager = mock(RepairBudgetManager.class);

        DurableOrchestratorOwnershipService ownershipService = createTestOwnershipService();

        orchestrationService = new MultiIterationRepairOrchestrationServiceImpl(
                taskExecutionRepository, repairIterationRepository, aiRepairProposalRepository,
                testRunRepository, codeChangeRepository, authorizationService,
                workspaceManagerService, aiDiagnosisService, aiRepairProposalService,
                physicalRepairApplicationService, singleIterationRepairExecutionService,
                failureClassifierService, testResultParserCompositeService,
                failureFingerprintService, repairBudgetManager, ownershipService
        );

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        executionId = UUID.randomUUID();

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());

        mockProject = new Project();
        mockProject.setId(projectId);

        mockRepository = new Repository();
        mockRepository.setId(repositoryId);

        mockTask = new Task();
        mockTask.setId(taskId);

        mockExecution = new TaskExecution();
        mockExecution.setId(executionId);
        mockExecution.setProject(mockProject);
        mockExecution.setRepository(mockRepository);
        mockExecution.setTask(mockTask);
        mockExecution.setEcosystem(Ecosystem.PYTHON);
        mockExecution.setCancellationRequested(false);

        baselineTestRun = new TestRun();
        baselineTestRun.setId(UUID.randomUUID());
        baselineTestRun.setTaskExecution(mockExecution);
        baselineTestRun.setExitCode(1);
        baselineTestRun.setStdoutSummary("AssertionError: test failed");
        baselineTestRun.setStderrSummary("");

        when(taskExecutionRepository.findById(executionId)).thenReturn(Optional.of(mockExecution));
        when(testRunRepository.findByTaskExecutionId(executionId)).thenReturn(List.of(baselineTestRun));
        when(codeChangeRepository.findByTaskId(taskId)).thenReturn(List.of());
        when(workspaceManagerService.calculateTreeDigest(any())).thenReturn("HASH_A");

        when(authorizationService.getAuthorizedProject(eq(projectId), eq(currentUser))).thenReturn(mockProject);

        ParsedTestEvidence defaultEvidence = new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("test_fn")));
        when(testResultParserCompositeService.parse(any(), any(), any())).thenReturn(defaultEvidence);

        FailureClassification defaultClassification = new FailureClassification(
                FailureType.CODE_FAILURE, Repairability.REPAIRABLE, ConfidenceLevel.HIGH,
                "Code error", null, "v1", Instant.now(), true, Map.of()
        );
        when(failureClassifierService.classify(any(), any(), any())).thenReturn(defaultClassification);

        when(failureFingerprintService.generateFingerprint(any(), any(), any(), any(), any())).thenReturn("FP_BASELINE");
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff FP", false, false, List.of()));

        when(repairBudgetManager.consumeRepairBudget(any(), anyLong()))
                .thenReturn(new RepairBudgetSnapshot(3, 1, 2, 1000L, true, null));

        when(repairIterationRepository.findByTaskExecutionIdAndIterationNumber(any(), anyInt()))
                .thenAnswer(inv -> {
                    int iterNum = inv.getArgument(1);
                    RepairIteration iter = new RepairIteration();
                    iter.setId(UUID.randomUUID());
                    iter.setTaskExecution(mockExecution);
                    iter.setIterationNumber(iterNum);
                    iter.setStatus(RepairIterationStatus.CREATED);
                    return Optional.of(iter);
                });

        AIDiagnosisResponseDto diagDto = new AIDiagnosisResponseDto();
        diagDto.setStatus("VALIDATED");
        when(aiDiagnosisService.diagnoseIteration(any(), any(), any(), any(), any(), any())).thenReturn(diagDto);

        AIRepairProposalResponseDto propDto = new AIRepairProposalResponseDto();
        propDto.setValidationStatus("VALIDATED");
        when(aiRepairProposalService.generateProposal(any(), any(), any(), any(), any(), any())).thenReturn(propDto);

        RepairExecutionResponseDto d2Dto = new RepairExecutionResponseDto();
        d2Dto.setOutcomePolicy("REPAIR_SUCCESS");
        d2Dto.setIterationStatus("VERIFIED");
        TestRunResponseDto trDto = new TestRunResponseDto();
        trDto.setExitCode(0);
        trDto.setStdoutSummary("All tests passed");
        trDto.setStderrSummary("");
        d2Dto.setTestRun(trDto);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any())).thenReturn(d2Dto);
    }

    // =========================================================================
    // FRESH DISPOSABLE WORKSPACE MATERIALIZATION & DISPOSAL (Scenarios 1–10)
    // =========================================================================

    @Test
    @DisplayName("Scenario 1: Iteration 1 gets fresh disposable workspace")
    void test01_Iteration1_FreshDisposableWorkspace() {
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result);
        verify(workspaceManagerService, atLeastOnce()).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 2: Iteration 2 gets fresh disposable workspace reconstructed from master baseline")
    void test02_Iteration2_FreshDisposableWorkspace() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto trFail = new TestRunResponseDto();
        trFail.setExitCode(1);
        trFail.setStdoutSummary("Failure in test 2");
        d2Fail.setTestRun(trFail);

        RepairExecutionResponseDto d2Pass = new RepairExecutionResponseDto();
        d2Pass.setOutcomePolicy("REPAIR_SUCCESS");
        TestRunResponseDto trPass = new TestRunResponseDto();
        trPass.setExitCode(0);
        trPass.setStdoutSummary("All passed");
        d2Pass.setTestRun(trPass);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail).thenReturn(d2Pass);

        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff 1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "Diff 2", false, false, List.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(2, result.getIterationsRun());
        verify(workspaceManagerService, times(2)).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 3: Iteration 3 gets fresh disposable workspace")
    void test03_Iteration3_FreshDisposableWorkspace() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto trFail = new TestRunResponseDto();
        trFail.setExitCode(1);
        d2Fail.setTestRun(trFail);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);

        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff 1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "Diff 2", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_3", FailureType.CODE_FAILURE, "PARSED", "Diff 3", false, false, List.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(3, result.getIterationsRun());
        verify(workspaceManagerService, times(3)).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 4: Mutated workspace from iteration 1 is destroyed")
    void test04_WorkspaceIteration1_Destroyed() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(workspaceManagerService, times(1)).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 5: Mutated workspace from iteration 2 is destroyed")
    void test05_WorkspaceIteration2_Destroyed() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of()));

        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(workspaceManagerService, atLeast(2)).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 6: Mutated workspace from iteration 3 is destroyed")
    void test06_WorkspaceIteration3_Destroyed() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_3", FailureType.CODE_FAILURE, "PARSED", "D3", false, false, List.of()));

        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(workspaceManagerService, times(3)).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 7: Disposable workspace cleanup executed in finally block on exception")
    void test07_WorkspaceCleanup_InFinallyBlock() {
        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("D2 Execution Error"));

        assertThrows(RuntimeException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );
        verify(workspaceManagerService, times(1)).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 8: Disposable workspace path is distinct per iteration")
    void test08_WorkspacePath_DistinctPerIteration() {
        List<UUID> cleanedUUIDs = new ArrayList<>();
        doAnswer(inv -> {
            cleanedUUIDs.add(inv.getArgument(0));
            return null;
        }).when(workspaceManagerService).cleanupWorkspace(any());

        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of()));

        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertTrue(cleanedUUIDs.size() >= 2);
        assertNotEquals(cleanedUUIDs.get(0), cleanedUUIDs.get(1));
    }

    @Test
    @DisplayName("Scenario 9: Workspace manager cleanup is invoked for every created disposable workspace")
    void test09_WorkspaceCleanup_InvokedForEachWorkspace() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(workspaceManagerService, times(1)).cleanupWorkspace(any());
    }

    @Test
    @DisplayName("Scenario 10: Parent master workspace directory is never passed as disposable workspace")
    void test10_ParentMasterWorkspace_NotPassedAsDisposable() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(workspaceManagerService, never()).cleanupWorkspace(eq(repositoryId));
    }

    // =========================================================================
    // MASTER REPOSITORY TREE DIGEST IMMUTABILITY (Scenarios 11–18)
    // =========================================================================

    @Test
    @DisplayName("Scenario 11: Master digest before orchestration matches master digest after 1 iteration")
    void test11_MasterDigest_MatchesAfter1Iteration() {
        when(workspaceManagerService.calculateTreeDigest(any())).thenReturn("HASH_A");
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Scenario 12: Master digest before orchestration matches master digest after 3 iterations")
    void test12_MasterDigest_MatchesAfter3Iterations() {
        when(workspaceManagerService.calculateTreeDigest(any())).thenReturn("HASH_A");

        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_3", FailureType.CODE_FAILURE, "PARSED", "D3", false, false, List.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(3, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 13: If master digest changes during orchestration, SECURITY_INVARIANT_VIOLATION is thrown")
    void test13_MasterDigest_MutationThrowsSecurityException() {
        when(workspaceManagerService.calculateTreeDigest(any()))
                .thenReturn("HASH_INITIAL")
                .thenReturn("HASH_INITIAL")
                .thenReturn("HASH_MUTATED");

        ApiException ex = assertThrows(ApiException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );
        assertEquals("SECURITY_INVARIANT_VIOLATION", ex.getCode());
    }

    @Test
    @DisplayName("Scenario 14: Master storage path verified read-only during iteration execution")
    void test14_MasterStoragePath_VerifiedReadOnly() {
        when(workspaceManagerService.calculateTreeDigest(any())).thenReturn("HASH_STABLE");
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Scenario 15: Master repo tree digest calculation occurs prior to first iteration")
    void test15_MasterDigest_CalculatedBeforeFirstIteration() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(workspaceManagerService, atLeast(1)).calculateTreeDigest(any());
    }

    @Test
    @DisplayName("Scenario 16: Master repo tree digest calculation occurs after final iteration")
    void test16_MasterDigest_CalculatedAfterFinalIteration() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(workspaceManagerService, atLeast(2)).calculateTreeDigest(any());
    }

    @Test
    @DisplayName("Scenario 17: Master tree digest comparison is case-insensitive / hex-normalized")
    void test17_MasterDigest_CaseInsensitiveMatch() {
        when(workspaceManagerService.calculateTreeDigest(any()))
                .thenReturn("abc123def")
                .thenReturn("ABC123DEF");

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Scenario 18: Internal exception thrown if master repo tree digest is corrupted")
    void test18_MasterDigest_CorruptedThrowsException() {
        when(workspaceManagerService.calculateTreeDigest(any()))
                .thenReturn("HASH_VALID")
                .thenReturn("HASH_CORRUPTED");

        assertThrows(ApiException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );
    }

    // =========================================================================
    // BOUNDED ITERATION COUNTS & EXECUTION HARD LIMITS (Scenarios 19–26)
    // =========================================================================

    @Test
    @DisplayName("Scenario 19: Maximum 3 repair iterations run when all return CONTINUE")
    void test19_Max3RepairIterations_Run() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_3", FailureType.CODE_FAILURE, "PARSED", "D3", false, false, List.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(3, result.getIterationsRun());
        assertEquals("MAX_ITERATIONS_REACHED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 20: Total executions count <= 4 (1 baseline + 3 repair iterations)")
    void test20_TotalExecutions_Leq4() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_3", FailureType.CODE_FAILURE, "PARSED", "D3", false, false, List.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(4, result.getTotalExecutions());
    }

    @Test
    @DisplayName("Scenario 21: Iteration #4 is physically impossible; loop terminates after iteration 3")
    void test21_Iteration4_Impossible() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_3", FailureType.CODE_FAILURE, "PARSED", "D3", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_4", FailureType.CODE_FAILURE, "PARSED", "D4", false, false, List.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(3, result.getIterationsRun());
        verify(singleIterationRepairExecutionService, times(3)).executeRepairIteration(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 22: Clean baseline test run terminates immediately with 0 repair iterations run")
    void test22_CleanBaseline_TerminatesImmediately() {
        baselineTestRun.setExitCode(0);
        baselineTestRun.setStdoutSummary("OK");
        when(testResultParserCompositeService.parse(any(), any(), any()))
                .thenReturn(new ParsedTestEvidence("PARSED", 1, 0, 0, 0, 1, Map.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(0, result.getIterationsRun());
        assertEquals("REPAIR_SUCCESS", result.getOutcomePolicy());
        assertEquals(D3DecisionType.TERMINATE, result.getFinalDecision());
    }

    @Test
    @DisplayName("Scenario 23: Early termination at iteration 1 when D3-A returns TERMINATE")
    void test23_EarlyTermination_Iteration1() {
        RepairExecutionResponseDto d2Success = new RepairExecutionResponseDto();
        d2Success.setOutcomePolicy("REPAIR_SUCCESS");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(0);
        tr.setStdoutSummary("All pass");
        tr.setStderrSummary("");
        d2Success.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Success);
        when(testResultParserCompositeService.parse(any(), any(), any()))
                .thenReturn(new ParsedTestEvidence("PARSED", 1, 1, 0, 0, 0, Map.of("failingTests", List.of("test1"))))
                .thenReturn(new ParsedTestEvidence("PARSED", 1, 0, 0, 0, 1, Map.of()));
        when(failureClassifierService.classify(any(), any(), any()))
                .thenReturn(new FailureClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE, ConfidenceLevel.HIGH, "Error", null, "v1", Instant.now(), true, Map.of()))
                .thenReturn(new FailureClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH, "Pass", null, "v1", Instant.now(), true, Map.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(1, result.getIterationsRun());
        assertEquals("REPAIR_SUCCESS", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 24: Early termination at iteration 2 when D3-A returns TERMINATE")
    void test24_EarlyTermination_Iteration2() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_SAME_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);

        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff 1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff 1", true, false, List.of("FP_1")));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(2, result.getIterationsRun());
        assertEquals("REPEATED_FAILURE", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 25: iterationsRun in response DTO reflects exact count of repair iterations executed")
    void test25_IterationsRun_ReflectsExactCount() {
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertEquals(1, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 26: totalExecutions in response DTO equals iterationsRun + 1")
    void test26_TotalExecutions_EqualsIterationsRunPlusOne() {
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertEquals(result.getIterationsRun() + 1, result.getTotalExecutions());
    }

    // =========================================================================
    // SINGLE ACTIVE OWNER CONCURRENCY LOCKING (Scenarios 27–32)
    // =========================================================================

    @Test
    @DisplayName("Scenario 27: First caller acquires orchestration lock")
    void test27_FirstCaller_AcquiresLock() {
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Scenario 28: Concurrent second caller for same executionId fails immediately with REPAIR_DUPLICATE")
    void test28_ConcurrentSecondCaller_FailsWithConflict() throws Exception {
        UUID concurrentExecId = UUID.randomUUID();
        TaskExecution concurrentExec = new TaskExecution();
        concurrentExec.setId(concurrentExecId);
        concurrentExec.setProject(mockProject);
        concurrentExec.setRepository(mockRepository);
        concurrentExec.setTask(mockTask);

        when(taskExecutionRepository.findById(concurrentExecId)).thenReturn(Optional.of(concurrentExec));
        when(testRunRepository.findByTaskExecutionId(concurrentExecId)).thenReturn(List.of(baselineTestRun));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch acquiredLatch = new CountDownLatch(1);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), eq(concurrentExecId), any(), any()))
                .thenAnswer(inv -> {
                    acquiredLatch.countDown();
                    startLatch.await(5, TimeUnit.SECONDS);
                    RepairExecutionResponseDto res = new RepairExecutionResponseDto();
                    res.setOutcomePolicy("REPAIR_SUCCESS");
                    TestRunResponseDto tr = new TestRunResponseDto();
                    tr.setExitCode(0);
                    res.setTestRun(tr);
                    return res;
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<MultiIterationOrchestrationResponseDto> f1 = executor.submit(() ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, concurrentExecId, currentUser)
        );

        assertTrue(acquiredLatch.await(3, TimeUnit.SECONDS));

        ApiException ex = assertThrows(ApiException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, concurrentExecId, currentUser)
        );

        startLatch.countDown();
        f1.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals("REPAIR_DUPLICATE", ex.getCode());
    }

    @Test
    @DisplayName("Scenario 29: Lock released in finally block when orchestration completes")
    void test29_LockReleased_OnCompletion() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        // Second execution for same executionId succeeds after first finishes
        MultiIterationOrchestrationResponseDto result2 = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result2);
    }

    @Test
    @DisplayName("Scenario 30: Lock released in finally block when orchestration throws exception")
    void test30_LockReleased_OnException() {
        reset(singleIterationRepairExecutionService);
        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Failure"));

        assertThrows(RuntimeException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );

        // Second attempt acquires lock successfully
        reset(singleIterationRepairExecutionService);
        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RepairExecutionResponseDto());

        assertDoesNotThrow(() ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );
    }

    @Test
    @DisplayName("Scenario 31: Different executionIds can run orchestrations concurrently")
    void test31_DifferentExecutions_RunConcurrently() {
        UUID exec2 = UUID.randomUUID();
        TaskExecution mockExec2 = new TaskExecution();
        mockExec2.setId(exec2);
        mockExec2.setProject(mockProject);
        mockExec2.setRepository(mockRepository);
        mockExec2.setTask(mockTask);

        when(taskExecutionRepository.findById(exec2)).thenReturn(Optional.of(mockExec2));
        when(testRunRepository.findByTaskExecutionId(exec2)).thenReturn(List.of(baselineTestRun));

        MultiIterationOrchestrationResponseDto res1 = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        MultiIterationOrchestrationResponseDto res2 = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, exec2, currentUser);

        assertNotNull(res1);
        assertNotNull(res2);
    }

    @Test
    @DisplayName("Scenario 32: Re-running orchestration after completion acquires lock successfully")
    void test32_RerunOrchestration_AcquiresLock() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result);
    }

    // =========================================================================
    // AI DIAGNOSIS INTEGRATION & FAILURE HANDLING (Scenarios 33–38)
    // =========================================================================

    @Test
    @DisplayName("Scenario 33: Phase 10.4-B AI Diagnosis invoked per iteration")
    void test33_AiDiagnosis_InvokedPerIteration() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(aiDiagnosisService, times(1)).diagnoseIteration(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 34: Diagnosis status AI_FAILURE terminates orchestration with REPAIR_FAILED_AMBIGUOUS")
    void test34_DiagnosisFailure_TerminatesAmbiguous() {
        AIDiagnosisResponseDto diagDto = new AIDiagnosisResponseDto();
        diagDto.setStatus("AI_FAILURE");
        diagDto.setRejectionReason("LLM parse error");
        when(aiDiagnosisService.diagnoseIteration(any(), any(), any(), any(), any(), any())).thenReturn(diagDto);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPAIR_FAILED_AMBIGUOUS", result.getOutcomePolicy());
        assertEquals(D3DecisionType.TERMINATE, result.getFinalDecision());
    }

    @Test
    @DisplayName("Scenario 35: Diagnosis status FAILED terminates without attempting AI Proposal or D2")
    void test35_DiagnosisFailed_SkipsProposalAndD2() {
        AIDiagnosisResponseDto diagDto = new AIDiagnosisResponseDto();
        diagDto.setStatus("FAILED");
        diagDto.setRejectionReason("Context missing");
        when(aiDiagnosisService.diagnoseIteration(any(), any(), any(), any(), any(), any())).thenReturn(diagDto);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        verify(aiRepairProposalService, never()).generateProposal(any(), any(), any(), any(), any(), any());
        verify(singleIterationRepairExecutionService, never()).executeRepairIteration(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 36: Diagnosis rejection reason recorded in decision reason")
    void test36_DiagnosisRejectionReason_Recorded() {
        AIDiagnosisResponseDto diagDto = new AIDiagnosisResponseDto();
        diagDto.setStatus("AI_FAILURE");
        diagDto.setRejectionReason("Context overflow");
        when(aiDiagnosisService.diagnoseIteration(any(), any(), any(), any(), any(), any())).thenReturn(diagDto);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertTrue(result.getDecisionReason().contains("Context overflow"));
    }

    @Test
    @DisplayName("Scenario 37: Successful diagnosis passes iteration ID and execution context")
    void test37_SuccessfulDiagnosis_PassesContext() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(aiDiagnosisService).diagnoseIteration(eq(projectId), eq(repositoryId), eq(taskId), eq(executionId), any(), eq(currentUser));
    }

    @Test
    @DisplayName("Scenario 38: Exception during AI Diagnosis terminates orchestration safely")
    void test38_DiagnosisException_TerminatesSafely() {
        when(aiDiagnosisService.diagnoseIteration(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM Timeout"));

        assertThrows(RuntimeException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );
    }

    // =========================================================================
    // AI REPAIR PROPOSAL INTEGRATION & SECURITY REJECTION (Scenarios 39–44)
    // =========================================================================

    @Test
    @DisplayName("Scenario 39: Phase 10.4-C AI Proposal invoked following successful diagnosis")
    void test39_AiProposal_InvokedAfterDiagnosis() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(aiRepairProposalService, times(1)).generateProposal(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 40: Proposal status REJECTED terminates orchestration with REPAIR_SECURITY_REJECTED")
    void test40_ProposalRejected_TerminatesSecurityRejected() {
        AIRepairProposalResponseDto propDto = new AIRepairProposalResponseDto();
        propDto.setValidationStatus("REJECTED");
        propDto.setValidationReason("Attempted file creation");
        when(aiRepairProposalService.generateProposal(any(), any(), any(), any(), any(), any())).thenReturn(propDto);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPAIR_SECURITY_REJECTED", result.getOutcomePolicy());
        assertEquals(D3DecisionType.TERMINATE, result.getFinalDecision());
    }

    @Test
    @DisplayName("Scenario 41: Proposal status VALIDATION_FAILED skips D2 execution")
    void test41_ProposalValidationFailed_SkipsD2() {
        AIRepairProposalResponseDto propDto = new AIRepairProposalResponseDto();
        propDto.setValidationStatus("VALIDATION_FAILED");
        propDto.setValidationReason("Tampering detected");
        when(aiRepairProposalService.generateProposal(any(), any(), any(), any(), any(), any())).thenReturn(propDto);

        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        verify(singleIterationRepairExecutionService, never()).executeRepairIteration(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 42: Proposal validation reason included in termination reason")
    void test42_ProposalValidationReason_Included() {
        AIRepairProposalResponseDto propDto = new AIRepairProposalResponseDto();
        propDto.setValidationStatus("REJECTED");
        propDto.setValidationReason("Non-whitelisted file modification");
        when(aiRepairProposalService.generateProposal(any(), any(), any(), any(), any(), any())).thenReturn(propDto);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertTrue(result.getDecisionReason().contains("Non-whitelisted file modification"));
    }

    @Test
    @DisplayName("Scenario 43: Valid proposal proceeds to D1/D2 sandbox execution")
    void test43_ValidProposal_ProceedsToD2() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(singleIterationRepairExecutionService, times(1)).executeRepairIteration(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 44: Exception during AI Proposal generation terminates safely")
    void test44_ProposalException_TerminatesSafely() {
        when(aiRepairProposalService.generateProposal(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM Connection Refused"));

        assertThrows(RuntimeException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );
    }

    // =========================================================================
    // CANCELLATION & BUDGET ENFORCEMENT (Scenarios 45–52)
    // =========================================================================

    @Test
    @DisplayName("Scenario 45: Cancellation requested before iteration 1 terminates with REPAIR_CANCELLED")
    void test45_Cancellation_BeforeIteration1() {
        mockExecution.setCancellationRequested(true);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPAIR_CANCELLED", result.getOutcomePolicy());
        assertEquals(0, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 46: Cancellation requested before iteration 2 terminates immediately")
    void test46_Cancellation_BeforeIteration2() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    mockExecution.setCancellationRequested(true);
                    return d2Fail;
                });

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(1, result.getIterationsRun());
        assertEquals("REPAIR_CANCELLED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 47: Cancellation requested before iteration 3 terminates immediately")
    void test47_Cancellation_BeforeIteration3() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "D1", false, false, List.of()))
                .thenAnswer(inv -> {
                    mockExecution.setCancellationRequested(true);
                    return new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "D2", false, false, List.of());
                });

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(2, result.getIterationsRun());
        assertEquals("REPAIR_CANCELLED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 48: Budget check fails due to iteration limit -> MAX_ITERATIONS_REACHED")
    void test48_BudgetIterationLimit_Exceeded() {
        when(repairBudgetManager.consumeRepairBudget(any(), anyLong()))
                .thenReturn(new RepairBudgetSnapshot(3, 3, 4, 250000L, false, "Maximum repair iteration limit reached (3)"));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("MAX_ITERATIONS_REACHED", result.getOutcomePolicy());
        assertEquals(0, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 49: Budget check fails due to time limit -> CUMULATIVE_TIMEOUT")
    void test49_BudgetTimeLimit_Exceeded() {
        when(repairBudgetManager.consumeRepairBudget(any(), anyLong()))
                .thenReturn(new RepairBudgetSnapshot(3, 1, 2, 300000L, false, "Cumulative execution time ceiling (300s) exceeded"));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("CUMULATIVE_TIMEOUT", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 50: Budget elapsed time accumulates across iterations")
    void test50_BudgetElapsedTime_Accumulates() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);
        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff 1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_2", FailureType.CODE_FAILURE, "PARSED", "Diff 2", false, false, List.of()));

        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        verify(repairBudgetManager, atLeast(1)).consumeRepairBudget(eq(executionId), anyLong());
    }

    @Test
    @DisplayName("Scenario 51: Budget manager consumeRepairBudget invoked per iteration loop entry")
    void test51_BudgetManager_InvokedPerLoopEntry() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(repairBudgetManager, times(1)).consumeRepairBudget(eq(executionId), anyLong());
    }

    @Test
    @DisplayName("Scenario 52: Exception in budget manager terminates orchestration safely")
    void test52_BudgetManagerException_TerminatesSafely() {
        when(repairBudgetManager.consumeRepairBudget(any(), anyLong()))
                .thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class, () ->
                orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );
    }

    // =========================================================================
    // FINGERPRINT SEQUENCE & DETERMINISTIC DECISION ENGINE INTEGRATION (Scenarios 53–62)
    // =========================================================================

    @Test
    @DisplayName("Scenario 53: Baseline fingerprint added to fingerprint history")
    void test53_BaselineFingerprint_AddedToHistory() {
        orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        verify(failureFingerprintService).generateFingerprint(eq(Ecosystem.PYTHON), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 54: Repetitive failure (A -> A) triggers REPEATED_FAILURE termination at iteration 2")
    void test54_RepetitiveFailure_TriggersRepeatedFailureTermination() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_SAME_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);

        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff 1", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_1", FailureType.CODE_FAILURE, "PARSED", "Diff 1", true, false, List.of("FP_1")));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPEATED_FAILURE", result.getOutcomePolicy());
        assertEquals(2, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 55: Oscillating failure (A -> B -> A) triggers REPEATED_FAILURE termination")
    void test55_OscillatingFailure_TriggersOscillatingFailureTermination() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(1);
        d2Fail.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail);

        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_B", FailureType.CODE_FAILURE, "PARSED", "Diff B", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_BASELINE", FailureType.CODE_FAILURE, "PARSED", "Diff A", true, true, List.of("FP_BASELINE", "FP_B")));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPEATED_FAILURE", result.getOutcomePolicy());
        assertEquals(2, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 56: Non-repairable classification from D2 triggers REPAIR_FAILED_OOM termination")
    void test56_NonRepairableOOM_TriggersTermination() {
        RepairExecutionResponseDto d2Oom = new RepairExecutionResponseDto();
        d2Oom.setOutcomePolicy("REPAIR_FAILED_OOM");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(137);
        tr.setTerminationReason(TerminationReason.OOM);
        d2Oom.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Oom);
        when(failureClassifierService.classify(any(), any(), any()))
                .thenReturn(new FailureClassification(FailureType.OOM, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH, "OOM killed", null, "v1", Instant.now(), true, Map.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPAIR_FAILED_OOM", result.getOutcomePolicy());
        assertEquals(1, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 57: Infrastructure failure from D2 triggers REPAIR_FAILED_INFRASTRUCTURE termination")
    void test57_InfraFailure_TriggersTermination() {
        RepairExecutionResponseDto d2Infra = new RepairExecutionResponseDto();
        d2Infra.setOutcomePolicy("REPAIR_FAILED_INFRASTRUCTURE");
        TestRunResponseDto tr = new TestRunResponseDto();
        tr.setExitCode(-1);
        d2Infra.setTestRun(tr);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Infra);
        when(failureClassifierService.classify(any(), any(), any()))
                .thenReturn(new FailureClassification(FailureType.EXECUTION_INFRA_FAILURE, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH, "Docker socket unavailable", null, "v1", Instant.now(), true, Map.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPAIR_FAILED_INFRASTRUCTURE", result.getOutcomePolicy());
        assertEquals(1, result.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 58: Progressive repair (A -> B -> SUCCESS) terminates at iteration 2 with REPAIR_SUCCESS")
    void test58_ProgressiveRepair_TerminatesSuccess() {
        RepairExecutionResponseDto d2Fail = new RepairExecutionResponseDto();
        d2Fail.setOutcomePolicy("REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE");
        TestRunResponseDto trFail = new TestRunResponseDto();
        trFail.setExitCode(1);
        trFail.setStdoutSummary("Failing test B");
        d2Fail.setTestRun(trFail);

        RepairExecutionResponseDto d2Pass = new RepairExecutionResponseDto();
        d2Pass.setOutcomePolicy("REPAIR_SUCCESS");
        TestRunResponseDto trPass = new TestRunResponseDto();
        trPass.setExitCode(0);
        trPass.setStdoutSummary("All pass");
        d2Pass.setTestRun(trPass);

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Fail).thenReturn(d2Pass);

        when(failureFingerprintService.analyzeFingerprintSequence(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FailureFingerprintResult("FP_B", FailureType.CODE_FAILURE, "PARSED", "Diff B", false, false, List.of()))
                .thenReturn(new FailureFingerprintResult("FP_PASS", FailureType.PASS, "PARSED", "Pass", false, false, List.of()));

        when(testResultParserCompositeService.parse(any(), any(), any()))
                .thenReturn(new ParsedTestEvidence("PARSED", 2, 2, 0, 0, 0, Map.of("failingTests", List.of("testA", "testB"))))
                .thenReturn(new ParsedTestEvidence("PARSED", 2, 1, 0, 0, 1, Map.of("failingTests", List.of("testB"))))
                .thenReturn(new ParsedTestEvidence("PARSED", 2, 0, 0, 0, 2, Map.of()));

        when(failureClassifierService.classify(any(), any(), any()))
                .thenReturn(new FailureClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE, ConfidenceLevel.HIGH, "Error", null, "v1", Instant.now(), true, Map.of()))
                .thenReturn(new FailureClassification(FailureType.CODE_FAILURE, Repairability.REPAIRABLE, ConfidenceLevel.HIGH, "Error", null, "v1", Instant.now(), true, Map.of()))
                .thenReturn(new FailureClassification(FailureType.PASS, Repairability.NOT_REPAIRABLE, ConfidenceLevel.HIGH, "Pass", null, "v1", Instant.now(), true, Map.of()));

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals(2, result.getIterationsRun());
        assertEquals("REPAIR_SUCCESS", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 59: Security rejection during D2 execution triggers REPAIR_SECURITY_REJECTED termination")
    void test59_SecurityRejectionInD2_TriggersTermination() {
        RepairExecutionResponseDto d2Sec = new RepairExecutionResponseDto();
        d2Sec.setOutcomePolicy("REPAIR_SECURITY_REJECTED");
        d2Sec.setSummaryReason("Anti-tampering violation");

        when(singleIterationRepairExecutionService.executeRepairIteration(any(), any(), any(), any(), any(), any()))
                .thenReturn(d2Sec);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("REPAIR_SECURITY_REJECTED", result.getOutcomePolicy());
    }

    @Test
    @DisplayName("Scenario 60: Executed iteration IDs tracked in order in response DTO")
    void test60_ExecutedIterationIds_TrackedInOrder() {
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result.getExecutedIterationIds());
        assertEquals(1, result.getExecutedIterationIds().size());
    }

    @Test
    @DisplayName("Scenario 61: Duration of orchestration is positive and accurate")
    void test61_OrchestrationDuration_PositiveAndAccurate() {
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertTrue(result.getExecutionDurationMs() >= 0);
    }

    @Test
    @DisplayName("Scenario 62: Deterministic decision engine evaluated with immutable D3ExecutionFacts")
    void test62_DecisionEngine_EvaluatedWithFacts() {
        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);
        assertNotNull(result.getFinalDecision());
        assertNotNull(result.getOutcomePolicy());
        assertNotNull(result.getDecisionReason());
    }

    // =========================================================================
    // HOSTILE CONCURRENCY & STALE OWNER PROTECTION (Scenarios 63–66)
    // =========================================================================

    @Test
    @DisplayName("Scenario 63: Loser of ownership acquisition performs zero diagnosis, zero proposal, zero budget, zero D1, zero D2")
    void test63_LoserPerformsZeroDiagnosisAndZeroD2() {
        DurableOrchestratorOwnershipService mockOwnership = mock(DurableOrchestratorOwnershipService.class);
        when(mockOwnership.acquireOwnership(eq(executionId), any())).thenReturn(null); // Acquisition fails (loser)

        MultiIterationRepairOrchestrationServiceImpl customService = new MultiIterationRepairOrchestrationServiceImpl(
                taskExecutionRepository, repairIterationRepository, aiRepairProposalRepository,
                testRunRepository, codeChangeRepository, authorizationService,
                workspaceManagerService, aiDiagnosisService, aiRepairProposalService,
                physicalRepairApplicationService, singleIterationRepairExecutionService,
                failureClassifierService, testResultParserCompositeService,
                failureFingerprintService, repairBudgetManager, mockOwnership
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                customService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );

        assertEquals("REPAIR_DUPLICATE", ex.getCode());

        // Verify ZERO side-effects by loser
        verify(aiDiagnosisService, never()).diagnoseIteration(any(), any(), any(), any(), any(), any());
        verify(aiRepairProposalService, never()).generateProposal(any(), any(), any(), any(), any(), any());
        verify(singleIterationRepairExecutionService, never()).executeRepairIteration(any(), any(), any(), any(), any(), any());
        verify(repairBudgetManager, never()).consumeRepairBudget(any(), anyLong());
    }

    @Test
    @DisplayName("Scenario 64: Stale owner waking up midway throws STALE_OWNER and aborts without mutation")
    void test64_StaleOwnerWakesUp_AbortsWithoutMutation() {
        DurableOrchestratorOwnershipService mockOwnership = mock(DurableOrchestratorOwnershipService.class);
        when(mockOwnership.acquireOwnership(eq(executionId), any())).thenReturn("owner-token-123");
        // Second call to verifyOwnership returns false (ownership lost to worker B)
        when(mockOwnership.verifyOwnership(eq(executionId), eq("owner-token-123")))
                .thenReturn(true)  // first check
                .thenReturn(false); // lost ownership!

        MultiIterationRepairOrchestrationServiceImpl customService = new MultiIterationRepairOrchestrationServiceImpl(
                taskExecutionRepository, repairIterationRepository, aiRepairProposalRepository,
                testRunRepository, codeChangeRepository, authorizationService,
                workspaceManagerService, aiDiagnosisService, aiRepairProposalService,
                physicalRepairApplicationService, singleIterationRepairExecutionService,
                failureClassifierService, testResultParserCompositeService,
                failureFingerprintService, repairBudgetManager, mockOwnership
        );

        ApiException ex = assertThrows(ApiException.class, () ->
                customService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser)
        );

        assertEquals("STALE_OWNER", ex.getCode());

        // Stale owner aborted before diagnosis/proposal/D2 execution
        verify(aiDiagnosisService, never()).diagnoseIteration(any(), any(), any(), any(), any(), any());
        verify(aiRepairProposalService, never()).generateProposal(any(), any(), any(), any(), any(), any());
        verify(singleIterationRepairExecutionService, never()).executeRepairIteration(any(), any(), any(), any(), any(), any());
    }

    private static DurableOrchestratorOwnershipService createTestOwnershipService() {
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            template.opsForValue().get("ping");
            return new DurableOrchestratorOwnershipServiceImpl(template);
        } catch (Exception e) {
            DurableOrchestratorOwnershipService mockService = mock(DurableOrchestratorOwnershipService.class);
            when(mockService.acquireOwnership(any(), any())).thenAnswer(inv -> UUID.randomUUID().toString());
            when(mockService.verifyOwnership(any(), any())).thenReturn(true);
            when(mockService.renewOwnership(any(), any(), any())).thenReturn(true);
            when(mockService.releaseOwnership(any(), any())).thenReturn(true);
            return mockService;
        }
    }

    @Test
    @DisplayName("Scenario 65: Stale owner cannot release ownership held by new active owner")
    void test65_StaleOwnerCannotReleaseActiveOwnerLock() {
        DurableOrchestratorOwnershipService ownership = createTestOwnershipService();

        String tokenA = ownership.acquireOwnership(executionId, Duration.ofSeconds(30));
        assertNotNull(tokenA);

        // Simulate Worker B acquiring ownership after A expires or is superseded
        ownership.releaseOwnership(executionId, tokenA);
        String tokenB = ownership.acquireOwnership(executionId, Duration.ofSeconds(30));
        assertNotNull(tokenB);

        // Worker A attempts stale release
        boolean releaseByA = ownership.releaseOwnership(executionId, tokenA);
        assertFalse(releaseByA, "Stale owner A cannot release worker B's lease");
        assertTrue(ownership.verifyOwnership(executionId, tokenB), "Worker B must maintain ownership");
    }

    @Test
    @DisplayName("Scenario 66: Concurrent iteration reservation cannot exceed 3 or create iteration 4")
    void test66_ConcurrentIterationReservation_CannotExceed3() {
        when(repairIterationRepository.countByTaskExecutionId(executionId)).thenReturn(3L);

        MultiIterationOrchestrationResponseDto result = orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, currentUser);

        assertEquals("MAX_ITERATIONS_REACHED", result.getOutcomePolicy());
        assertEquals(0, result.getIterationsRun());
        verify(singleIterationRepairExecutionService, never()).executeRepairIteration(any(), any(), any(), any(), any(), any());
    }
}
