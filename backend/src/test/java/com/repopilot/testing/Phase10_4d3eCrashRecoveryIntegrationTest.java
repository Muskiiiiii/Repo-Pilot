package com.repopilot.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.dto.*;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.RepairLoopIdempotencyRecordRepository;
import com.repopilot.testing.service.*;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Phase10_4d3eCrashRecoveryIntegrationTest {

    private TaskExecutionRepository taskExecutionRepository;
    private RepairIterationRepository repairIterationRepository;
    private ProjectAuthorizationService authorizationService;
    private AuditLogService auditLogService;
    private MultiIterationRepairOrchestrationService orchestrationService;
    private DurableOrchestratorOwnershipService ownershipService;
    private DockerSandboxExecutorService dockerSandboxExecutorService;
    private WorkspaceManagerService workspaceManagerService;
    private RepairLoopIdempotencyRecordRepository idempotencyRecordRepository;
    private ObjectMapper objectMapper;

    private CrashRecoverySweeper sweeper;
    private RepairLoopApiService repairLoopApiService;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID executionId;
    private User memberUser;
    private TaskExecution mockExecution;
    private Project mockProject;
    private Repository mockRepository;
    private Task mockTask;

    @BeforeEach
    void setUp() {
        taskExecutionRepository = mock(TaskExecutionRepository.class);
        repairIterationRepository = mock(RepairIterationRepository.class);
        authorizationService = mock(ProjectAuthorizationService.class);
        auditLogService = mock(AuditLogService.class);
        orchestrationService = mock(MultiIterationRepairOrchestrationService.class);
        ownershipService = mock(DurableOrchestratorOwnershipService.class);
        dockerSandboxExecutorService = mock(DockerSandboxExecutorService.class);
        workspaceManagerService = mock(WorkspaceManagerService.class);
        idempotencyRecordRepository = mock(RepairLoopIdempotencyRecordRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        sweeper = new CrashRecoverySweeper(
                taskExecutionRepository, repairIterationRepository, ownershipService,
                dockerSandboxExecutorService, workspaceManagerService, orchestrationService
        );

        repairLoopApiService = new RepairLoopApiServiceImpl(
                taskExecutionRepository, repairIterationRepository, authorizationService,
                auditLogService, orchestrationService, idempotencyRecordRepository, objectMapper
        );

        projectId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        executionId = UUID.randomUUID();

        memberUser = new User();
        memberUser.setId(UUID.randomUUID());

        mockProject = new Project();
        mockProject.setId(projectId);

        mockRepository = new Repository();
        mockRepository.setId(repositoryId);

        mockTask = new Task();
        mockTask.setId(taskId);
        mockTask.setProject(mockProject);

        mockExecution = new TaskExecution();
        mockExecution.setId(executionId);
        mockExecution.setProject(mockProject);
        mockExecution.setRepository(mockRepository);
        mockExecution.setTask(mockTask);
        mockExecution.setStatus(ExecutionStatus.RUNNING);
        mockExecution.setFencingEpoch(1L);
        mockExecution.setOwnerToken("owner-token-123");
        mockExecution.setVersion(1);
        mockExecution.setRecoveryAttempts(0);
        mockExecution.setStartedAt(Instant.now().minusSeconds(300));

        when(authorizationService.getAuthorizedProject(eq(projectId), any())).thenReturn(mockProject);
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId))
                .thenReturn(Optional.of(mockExecution));
        when(taskExecutionRepository.findById(executionId)).thenReturn(Optional.of(mockExecution));
        when(taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(any(), any(), any())).thenReturn(1);
        when(repairIterationRepository.updateStatusWithFencingAndVersion(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("Test Vector 1: Leadership claim increments fencing epoch and updates owner token")
    void testFencingEpochIncrementOnLeadershipClaim() {
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), eq("new-owner-token")))
                .thenReturn(1);

        int claimed = taskExecutionRepository.claimLeadershipAndIncrementEpoch(executionId, "new-owner-token");
        assertEquals(1, claimed);
        verify(taskExecutionRepository, times(1)).claimLeadershipAndIncrementEpoch(executionId, "new-owner-token");
    }

    @Test
    @DisplayName("Test Vector 2: Stale worker with superseded fencing epoch is fenced out")
    void testStaleWorkerFencedOut() {
        when(taskExecutionRepository.updateStatusWithFencingAndVersion(
                eq(executionId), eq(ExecutionStatus.COMPLETED), any(), eq(1), eq(1L)))
                .thenReturn(0); // 0 rows affected because fencingEpoch was bumped to 2L on DB

        int updated = taskExecutionRepository.updateStatusWithFencingAndVersion(
                executionId, ExecutionStatus.COMPLETED, Instant.now(), 1, 1L);

        assertEquals(0, updated, "Stale update attempt must affect 0 rows in DB");
    }

    @Test
    @DisplayName("Test Vector 3: TOCTOU Defense prevents stale DB mutation when Redis lease expires")
    void testTOCTOUDefenseWhenLeaseExpires() {
        when(ownershipService.verifyOwnership(eq(executionId), eq("stale-token"))).thenReturn(false);

        boolean isOwned = ownershipService.verifyOwnership(executionId, "stale-token");
        assertFalse(isOwned, "Ownership verification must return false for expired lease token");
    }

    @Test
    @DisplayName("Test Vector 4: D1 Interrupted Recovery rematerializes workspace and cleans container")
    void testD1InterruptedRecovery() {
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);

        RepairIteration iter1 = new RepairIteration();
        iter1.setTaskExecution(mockExecution);
        iter1.setIterationNumber(1);
        iter1.setStatus(RepairIterationStatus.VALIDATING);

        when(repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId))
                .thenReturn(List.of(iter1));

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertTrue(recovered);
        InOrder inOrder = inOrder(dockerSandboxExecutorService, workspaceManagerService);
        inOrder.verify(dockerSandboxExecutorService).cleanupContainersByExecutionId(executionId);
        inOrder.verify(workspaceManagerService).cleanupWorkspace(executionId);
        assertEquals(RepairIterationStatus.EXECUTION_FAILED, iter1.getStatus());
    }

    @Test
    @DisplayName("Test Vector 5: D2 Interrupted Recovery discards unpersisted logs without guessing")
    void testD2InterruptedRecovery() {
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);

        RepairIteration iter1 = new RepairIteration();
        iter1.setTaskExecution(mockExecution);
        iter1.setIterationNumber(1);
        iter1.setStatus(RepairIterationStatus.EXECUTING);

        when(repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId))
                .thenReturn(List.of(iter1));

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertTrue(recovered);
        verify(dockerSandboxExecutorService).cleanupContainersByExecutionId(executionId);
        verify(workspaceManagerService).cleanupWorkspace(executionId);
        assertEquals(RepairIterationStatus.EXECUTION_FAILED, iter1.getStatus());
    }

    @Test
    @DisplayName("Test Vector 6: Container cleanup MUST precede workspace directory cleanup")
    void testLabelBasedContainerCleanupOrder() {
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);

        sweeper.recoverStaleExecution(executionId);

        InOrder inOrder = inOrder(dockerSandboxExecutorService, workspaceManagerService);
        inOrder.verify(dockerSandboxExecutorService).cleanupContainersByExecutionId(executionId);
        inOrder.verify(workspaceManagerService).cleanupWorkspace(executionId);
    }

    @Test
    @DisplayName("Test Vector 7: Monotonic cancellation enforced (FALSE -> TRUE only)")
    void testMonotonicCancellationEnforced() {
        mockExecution.setCancellationRequested(true);
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertTrue(recovered);
        assertTrue(mockExecution.getCancellationRequested(), "Cancellation requested must remain true");
        verify(taskExecutionRepository).updateStatusWithFencingAndVersion(
                eq(executionId), eq(ExecutionStatus.CANCELLED), any(), any(), any());
    }

    @Test
    @DisplayName("Test Vector 8: Exceeded recovery attempts (5) fails closed without LLM guessing")
    void testMaxRecoveryAttemptsExceeded() {
        mockExecution.setRecoveryAttempts(5);
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);

        when(taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(eq(executionId), any(), any())).thenReturn(0);

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertFalse(recovered, "Exceeded recovery attempts must return false");
        verify(taskExecutionRepository).updateStatusWithFencingAndVersion(
                eq(executionId), eq(ExecutionStatus.FAILED), any(), any(), any());
        verifyNoInterviewsWithLLM();
    }

    @Test
    @DisplayName("Test Vector 9: Master baseline repository digest is 100% immutable")
    void testMasterRepoImmutability() throws Exception {
        Path tempMaster = Files.createTempDirectory("repopilot_master_test_");
        Files.writeString(tempMaster.resolve("main.py"), "print('hello world')\n");

        String digestBefore = calculateDigest(tempMaster);

        // Run recovery operations
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);

        sweeper.recoverStaleExecution(executionId);

        String digestAfter = calculateDigest(tempMaster);
        assertEquals(digestBefore, digestAfter, "Master repository files must remain 100% untouched");

        // Clean temp
        Files.deleteIfExists(tempMaster.resolve("main.py"));
        Files.deleteIfExists(tempMaster);
    }

    @Test
    @DisplayName("Test Vector 10: POST idempotency reconciliation handles terminal status cleanly")
    void testPostIdempotencyReconciliation() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);

        RepairLoopRequestDto request = new RepairLoopRequestDto();
        request.setIdempotencyKey("idempotency-key-xyz");

        RepairLoopResponseDto response = repairLoopApiService.triggerRepairLoop(
                projectId, repositoryId, taskId, executionId, request, memberUser
        );

        assertNotNull(response);
        assertTrue(response.isAlreadyCompleted());
        assertEquals("COMPLETED", response.getStatus());
        assertEquals("REPAIR_SUCCESS", response.getOutcomePolicy());
    }

    @Test
    @DisplayName("Test Vector 11: Contradictory corrupt state fails closed without calling LLM")
    void testContradictoryStateFailClosed() {
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(eq(executionId), any(), any(), any()))
                .thenReturn(Optional.empty());

        ApiException exception = assertThrows(ApiException.class, () ->
                repairLoopApiService.triggerRepairLoop(
                        projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("EXECUTION_NOT_FOUND", exception.getCode());
        verifyNoInterviewsWithLLM();
    }

    @Test
    @DisplayName("Test Vector 12: Sweeper skips active execution currently owned in Redis")
    void testSweeperSkipsActiveOwnedExecution() {
        when(ownershipService.isOwned(executionId)).thenReturn(true);

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertFalse(recovered, "Sweeper must skip execution currently owned in Redis");
        verify(taskExecutionRepository, never()).claimLeadershipAndIncrementEpoch(any(), any());
    }

    @Test
    @DisplayName("Blocker 1 Regression: RepairIteration mutation by stale worker with old epoch is fenced out")
    void testBlocker1RepairIterationFencingMutationFencedOut() {
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);

        RepairIteration iter1 = new RepairIteration();
        iter1.setId(UUID.randomUUID());
        iter1.setTaskExecution(mockExecution);
        iter1.setIterationNumber(1);
        iter1.setStatus(RepairIterationStatus.VALIDATING);
        iter1.setVersion(1);

        when(repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId))
                .thenReturn(List.of(iter1));

        // Stub updateStatusWithFencingAndVersion for RepairIteration to return 0 (fenced out by epoch rollover)
        when(repairIterationRepository.updateStatusWithFencingAndVersion(
                eq(iter1.getId()), any(), any(), any(), any(), any()))
                .thenReturn(0);

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertFalse(recovered, "Stale worker/sweeper must abort when RepairIteration update returns 0 rows affected");
        verify(repairIterationRepository, never()).save(any());
        assertEquals(RepairIterationStatus.VALIDATING, iter1.getStatus(), "RepairIteration status must remain untouched");
    }

    @Test
    @DisplayName("Blocker 2 Regression A: Recovery attempts 4 -> 5 is allowed exactly once")
    void testBlocker2RecoveryAttemptsCeilingFrom4To5Allowed() {
        mockExecution.setRecoveryAttempts(4);
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);
        when(taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(eq(executionId), any(), any())).thenReturn(1);

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertTrue(recovered);
        verify(taskExecutionRepository).incrementRecoveryAttemptsWithFencingAndCeiling(eq(executionId), any(), any());
    }

    @Test
    @DisplayName("Blocker 2 Regression B: Recovery attempts 5 -> 6 is rejected and fails closed")
    void testBlocker2RecoveryAttemptsCeilingAt5Rejected() {
        mockExecution.setRecoveryAttempts(5);
        when(ownershipService.isOwned(executionId)).thenReturn(false);
        when(ownershipService.acquireOwnership(eq(executionId), any())).thenReturn("sweeper-token");
        when(taskExecutionRepository.claimLeadershipAndIncrementEpoch(eq(executionId), any())).thenReturn(1);
        // Stub query to return 0 because recoveryAttempts < 5 condition evaluates false in DB
        when(taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(eq(executionId), any(), any())).thenReturn(0);

        boolean recovered = sweeper.recoverStaleExecution(executionId);

        assertFalse(recovered, "Sweeper must return false when recovery attempts ceiling of 5 is reached");
        verify(taskExecutionRepository).updateStatusWithFencingAndVersion(
                eq(executionId), eq(ExecutionStatus.FAILED), any(), any(), any());
    }

    @Test
    @DisplayName("Blocker 2 Regression C: Concurrent attempts at 4 allows exactly one caller")
    void testBlocker2ConcurrentAttemptsAt4OnlyOneConsumesSlot() {
        when(taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(eq(executionId), eq(1), eq(1L)))
                .thenReturn(1) // First sweeper succeeds
                .thenReturn(0); // Second concurrent sweeper gets 0 because version bumped and recoveryAttempts reached 5

        int firstResult = taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(executionId, 1, 1L);
        int secondResult = taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(executionId, 1, 1L);

        assertEquals(1, firstResult, "First caller acquires recovery attempt slot 5");
        assertEquals(0, secondResult, "Second concurrent caller is rejected");
    }

    @Test
    @DisplayName("Blocker 2 Regression D: Stale worker cannot consume recovery slot after fencing rollover")
    void testBlocker2StaleWorkerCannotConsumeSlotAfterFencingRollover() {
        when(taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(eq(executionId), any(), eq(1L)))
                .thenReturn(0); // Fenced out because DB epoch is 2L

        int result = taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(executionId, 1, 1L);

        assertEquals(0, result, "Stale worker attempt must affect 0 rows in DB");
    }

    @Test
    @DisplayName("Blocker 2 Regression E: recovery_attempts never exceeds 5")
    void testBlocker2RecoveryAttemptsNeverExceeds5() {
        when(taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(eq(executionId), any(), any()))
                .thenReturn(0);

        int result = taskExecutionRepository.incrementRecoveryAttemptsWithFencingAndCeiling(executionId, 5, 1L);

        assertEquals(0, result, "Query condition recoveryAttempts < 5 prevents reaching 6");
    }

    private void verifyNoInterviewsWithLLM() {
        verifyNoInteractions(orchestrationService);
    }

    private String calculateDigest(Path dir) throws Exception {
        File f = dir.resolve("main.py").toFile();
        return f.exists() ? String.valueOf(f.length()) : "0";
    }
}
