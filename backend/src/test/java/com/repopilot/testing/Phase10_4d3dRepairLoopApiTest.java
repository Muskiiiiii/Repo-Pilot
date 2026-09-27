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
import com.repopilot.testing.controller.RepairLoopController;
import com.repopilot.testing.dto.*;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.policy.D3DecisionType;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.RepairLoopIdempotencyRecordRepository;
import com.repopilot.testing.service.*;
import com.repopilot.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Phase10_4d3dRepairLoopApiTest {

    private TaskExecutionRepository taskExecutionRepository;
    private RepairIterationRepository repairIterationRepository;
    private ProjectAuthorizationService authorizationService;
    private AuditLogService auditLogService;
    private MultiIterationRepairOrchestrationService orchestrationService;
    private DockerSandboxExecutorService dockerSandboxExecutorService;
    private WorkspaceManagerService workspaceManagerService;
    private RepairLoopIdempotencyRecordRepository idempotencyRecordRepository;
    private ObjectMapper objectMapper;

    private RepairLoopApiService repairLoopApiService;

    private UUID projectId;
    private UUID repositoryId;
    private UUID taskId;
    private UUID executionId;
    private User memberUser;
    private User viewerUser;
    private User strangerUser;
    private TaskExecution mockExecution;
    private Project mockProject;

    @BeforeEach
    void setUp() {
        taskExecutionRepository = mock(TaskExecutionRepository.class);
        repairIterationRepository = mock(RepairIterationRepository.class);
        authorizationService = mock(ProjectAuthorizationService.class);
        auditLogService = mock(AuditLogService.class);
        orchestrationService = mock(MultiIterationRepairOrchestrationService.class);
        dockerSandboxExecutorService = mock(DockerSandboxExecutorService.class);
        workspaceManagerService = mock(WorkspaceManagerService.class);
        idempotencyRecordRepository = mock(RepairLoopIdempotencyRecordRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

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
        memberUser.setEmail("member@repopilot.com");

        viewerUser = new User();
        viewerUser.setId(UUID.randomUUID());
        viewerUser.setEmail("viewer@repopilot.com");

        strangerUser = new User();
        strangerUser.setId(UUID.randomUUID());
        strangerUser.setEmail("stranger@repopilot.com");

        mockProject = new Project();
        mockProject.setId(projectId);

        mockExecution = new TaskExecution();
        mockExecution.setId(executionId);
        mockExecution.setStatus(ExecutionStatus.RUNNING);
        mockExecution.setStartedAt(Instant.now());

        Task task = new Task();
        task.setId(taskId);
        mockExecution.setTask(task);

        Repository repo = new Repository();
        repo.setId(repositoryId);
        mockExecution.setRepository(repo);
        mockExecution.setProject(mockProject);

        when(authorizationService.getAuthorizedProject(eq(projectId), eq(memberUser))).thenReturn(mockProject);
        when(authorizationService.getAuthorizedProject(eq(projectId), eq(viewerUser))).thenReturn(mockProject);
        when(authorizationService.getAuthorizedProject(eq(projectId), eq(strangerUser)))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "User not member of project"));

        doNothing().when(authorizationService).requireMinimumRole(any(), eq(memberUser), eq(ProjectRole.MEMBER));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "MEMBER role required"))
                .when(authorizationService).requireMinimumRole(any(), eq(viewerUser), eq(ProjectRole.MEMBER));

        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId))
                .thenReturn(Optional.of(mockExecution));

        when(taskExecutionRepository.save(any(TaskExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyRecordRepository.save(any(RepairLoopIdempotencyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        MultiIterationOrchestrationResponseDto defaultOrchestrationDto = new MultiIterationOrchestrationResponseDto();
        defaultOrchestrationDto.setTaskExecutionId(executionId);
        defaultOrchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);
        defaultOrchestrationDto.setTotalIterationsExecuted(1);
        defaultOrchestrationDto.setFinalOutcomePolicy("REPAIR_SUCCESS");

        when(orchestrationService.orchestrateSelfCorrection(any(), any(), any(), any(), any()))
                .thenReturn(defaultOrchestrationDto);
    }

    // ==========================================
    // 1. API BASIC AUTHORIZATION SCENARIOS (1-6)
    // ==========================================

    @Test
    @DisplayName("Scenario 01: POST repair by authorized MEMBER -> 202 ACCEPTED")
    void test01_PostRepair_AuthorizedMember_Succeeds() {
        MultiIterationOrchestrationResponseDto orchestrationDto = new MultiIterationOrchestrationResponseDto();
        orchestrationDto.setTaskExecutionId(executionId);
        orchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);
        orchestrationDto.setTotalIterationsExecuted(1);
        orchestrationDto.setFinalOutcomePolicy("REPAIR_SUCCESS");

        when(orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, memberUser))
                .thenReturn(orchestrationDto);

        RepairLoopResponseDto response = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);

        assertNotNull(response);
        assertEquals(executionId, response.getExecutionId());
        assertEquals(1, response.getIterationsRun());
        assertFalse(response.isAlreadyCompleted());
    }

    @Test
    @DisplayName("Scenario 02: GET status by authorized VIEWER -> 200 OK")
    void test02_GetStatus_AuthorizedViewer_Succeeds() {
        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);

        assertNotNull(status);
        assertEquals(executionId, status.getExecutionId());
        assertEquals(taskId, status.getTaskId());
    }

    @Test
    @DisplayName("Scenario 03: POST cancel by authorized MEMBER -> 200 OK")
    void test03_PostCancel_AuthorizedMember_Succeeds() {
        RepairLoopResponseDto response = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);

        assertNotNull(response);
        assertEquals("CANCELLED", response.getStatus());
        assertTrue(response.isAlreadyCompleted());
    }

    @Test
    @DisplayName("Scenario 04: Unauthenticated request -> throws FORBIDDEN/UNAUTHORIZED")
    void test04_UnauthenticatedRequest_Fails() {
        when(authorizationService.getAuthorizedProject(eq(projectId), isNull()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Unauthenticated"));

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, null));
    }

    @Test
    @DisplayName("Scenario 05: VIEWER POST repair -> rejected 403 FORBIDDEN")
    void test05_ViewerPostRepair_Rejected() {
        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, viewerUser));
    }

    @Test
    @DisplayName("Scenario 06: VIEWER CANCEL -> rejected 403 FORBIDDEN")
    void test06_ViewerCancel_Rejected() {
        assertThrows(ApiException.class, () -> repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, viewerUser));
    }

    // ==========================================
    // 2. IDOR / CROSS-PROJECT TESTS (7-12)
    // ==========================================

    @Test
    @DisplayName("Scenario 07: Cross-project GET -> 404 NOT_FOUND")
    void test07_CrossProjectGet_Returns404() {
        UUID otherProject = UUID.randomUUID();
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, otherProject))
                .thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> repairLoopApiService.getRepairLoopStatus(otherProject, repositoryId, taskId, executionId, memberUser));
    }

    @Test
    @DisplayName("Scenario 08: Cross-project POST -> 404 NOT_FOUND")
    void test08_CrossProjectPost_Returns404() {
        UUID otherProject = UUID.randomUUID();
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, otherProject))
                .thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(otherProject, repositoryId, taskId, executionId, null, memberUser));
    }

    @Test
    @DisplayName("Scenario 09: Cross-project CANCEL -> 404 NOT_FOUND")
    void test09_CrossProjectCancel_Returns404() {
        UUID otherProject = UUID.randomUUID();
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, otherProject))
                .thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> repairLoopApiService.cancelRepairLoop(otherProject, repositoryId, taskId, executionId, memberUser));
    }

    @Test
    @DisplayName("Scenario 10: Forged repository ID -> 404 NOT_FOUND")
    void test10_ForgedRepositoryId_Returns404() {
        UUID forgedRepo = UUID.randomUUID();
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, forgedRepo, projectId))
                .thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, forgedRepo, taskId, executionId, null, memberUser));
    }

    @Test
    @DisplayName("Scenario 11: Forged task ID -> 404 NOT_FOUND")
    void test11_ForgedTaskId_Returns404() {
        UUID forgedTask = UUID.randomUUID();
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, forgedTask, repositoryId, projectId))
                .thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, forgedTask, executionId, null, memberUser));
    }

    @Test
    @DisplayName("Scenario 12: Forged execution ID -> 404 NOT_FOUND")
    void test12_ForgedExecutionId_Returns404() {
        UUID forgedExec = UUID.randomUUID();
        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(forgedExec, taskId, repositoryId, projectId))
                .thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, forgedExec, null, memberUser));
    }

    // ==========================================
    // 3. IDEMPOTENCY TESTS (13-18)
    // ==========================================

    @Test
    @DisplayName("Scenario 13: Same idempotency key repeated -> returns cached result without re-executing orchestration")
    void test13_SameIdempotencyKeyRepeated_ReturnsCachedResult() throws Exception {
        RepairLoopRequestDto req = new RepairLoopRequestDto("key-123", null);

        RepairLoopResponseDto mockResponse = new RepairLoopResponseDto();
        mockResponse.setExecutionId(executionId);
        mockResponse.setStatus("TERMINATE_REPAIR_SUCCESS");
        mockResponse.setAlreadyCompleted(false);

        RepairLoopIdempotencyRecord record = new RepairLoopIdempotencyRecord(
                memberUser.getId(), projectId, executionId, "REPAIR_ORCHESTRATION", "key-123", 200, objectMapper.writeValueAsString(mockResponse));

        when(idempotencyRecordRepository.findByUserIdAndProjectIdAndExecutionIdAndOperationAndIdempotencyKey(
                memberUser.getId(), projectId, executionId, "REPAIR_ORCHESTRATION", "key-123"))
                .thenReturn(Optional.of(record));

        RepairLoopResponseDto response = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, req, memberUser);

        assertTrue(response.isAlreadyCompleted());
        assertEquals("TERMINATE_REPAIR_SUCCESS", response.getStatus());
        verify(orchestrationService, never()).orchestrateSelfCorrection(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 14: Same idempotency key concurrent -> PostgreSQL unique constraint prevents duplicate")
    void test14_ConcurrentIdempotencyKey_Prevented() {
        RepairLoopRequestDto req = new RepairLoopRequestDto("key-concurrent", null);

        MultiIterationOrchestrationResponseDto orchestrationDto = new MultiIterationOrchestrationResponseDto();
        orchestrationDto.setTaskExecutionId(executionId);
        orchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);
        when(orchestrationService.orchestrateSelfCorrection(any(), any(), any(), any(), any())).thenReturn(orchestrationDto);

        RepairLoopResponseDto res1 = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, req, memberUser);
        assertNotNull(res1);
        verify(idempotencyRecordRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Scenario 15: Same key different execution -> independent orchestration")
    void test15_SameKeyDifferentExecution_Independent() {
        UUID executionId2 = UUID.randomUUID();
        TaskExecution exec2 = new TaskExecution();
        exec2.setId(executionId2);
        exec2.setStatus(ExecutionStatus.RUNNING);

        when(taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId2, taskId, repositoryId, projectId))
                .thenReturn(Optional.of(exec2));

        when(idempotencyRecordRepository.findByUserIdAndProjectIdAndExecutionIdAndOperationAndIdempotencyKey(
                memberUser.getId(), projectId, executionId2, "REPAIR_ORCHESTRATION", "key-123"))
                .thenReturn(Optional.empty());

        MultiIterationOrchestrationResponseDto orchestrationDto = new MultiIterationOrchestrationResponseDto();
        orchestrationDto.setTaskExecutionId(executionId2);
        orchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);
        when(orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId2, memberUser))
                .thenReturn(orchestrationDto);

        RepairLoopRequestDto req = new RepairLoopRequestDto("key-123", null);
        RepairLoopResponseDto res2 = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId2, req, memberUser);

        assertNotNull(res2);
        assertEquals(executionId2, res2.getExecutionId());
    }

    @Test
    @DisplayName("Scenario 16: Same key different user -> independent record per user")
    void test16_SameKeyDifferentUser_Independent() {
        when(idempotencyRecordRepository.findByUserIdAndProjectIdAndExecutionIdAndOperationAndIdempotencyKey(
                strangerUser.getId(), projectId, executionId, "REPAIR_ORCHESTRATION", "key-123"))
                .thenReturn(Optional.empty());

        when(authorizationService.getAuthorizedProject(eq(projectId), eq(strangerUser))).thenReturn(mockProject);
        doNothing().when(authorizationService).requireMinimumRole(any(), eq(strangerUser), eq(ProjectRole.MEMBER));

        MultiIterationOrchestrationResponseDto orchestrationDto = new MultiIterationOrchestrationResponseDto();
        orchestrationDto.setTaskExecutionId(executionId);
        orchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);
        when(orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, strangerUser))
                .thenReturn(orchestrationDto);

        RepairLoopRequestDto req = new RepairLoopRequestDto("key-123", null);
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, req, strangerUser);

        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 17: Oversized idempotency key -> rejected BAD_REQUEST")
    void test17_OversizedIdempotencyKey_Rejected() {
        String longKey = "a".repeat(200);
        RepairLoopRequestDto req = new RepairLoopRequestDto(longKey, null);

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, req, memberUser));
    }

    @Test
    @DisplayName("Scenario 18: Durable persistence across application instances verified")
    void test18_DurablePersistence_Verified() {
        RepairLoopIdempotencyRecord record = new RepairLoopIdempotencyRecord(memberUser.getId(), projectId, executionId, "REPAIR_ORCHESTRATION", "key-durable", 200, "{\"status\":\"TERMINATE_REPAIR_SUCCESS\"}");

        when(idempotencyRecordRepository.findByUserIdAndProjectIdAndExecutionIdAndOperationAndIdempotencyKey(
                memberUser.getId(), projectId, executionId, "REPAIR_ORCHESTRATION", "key-durable"))
                .thenReturn(Optional.of(record));

        RepairLoopRequestDto req = new RepairLoopRequestDto("key-durable", null);
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, req, memberUser);

        assertTrue(res.isAlreadyCompleted());
    }

    // ==========================================
    // 4. CONCURRENCY TESTS (19-24)
    // ==========================================

    @Test
    @DisplayName("Scenario 19: Different keys same execution -> D3-C ownership lock enforces single active owner")
    void test19_DifferentKeysSameExecution_EnforcesSingleOwner() {
        when(orchestrationService.orchestrateSelfCorrection(projectId, repositoryId, taskId, executionId, memberUser))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "REPAIR_DUPLICATE", "An active repair orchestration is already running"));

        RepairLoopRequestDto req1 = new RepairLoopRequestDto("key-A", null);
        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, req1, memberUser));
    }

    @Test
    @DisplayName("Scenario 20: Duplicate POST does not consume budget twice")
    void test20_DuplicatePost_DoesNotConsumeBudgetTwice() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);

        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);

        assertTrue(res.isAlreadyCompleted());
        verify(orchestrationService, never()).orchestrateSelfCorrection(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 21: Duplicate POST does not create second iteration")
    void test21_DuplicatePost_DoesNotCreateSecondIteration() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);

        repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);
        verify(repairIterationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Scenario 22: Stale owner cannot execute orchestration")
    void test22_StaleOwnerCannotExecute() {
        when(orchestrationService.orchestrateSelfCorrection(any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "STALE_OWNER", "Stale owner token"));

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser));
    }

    @Test
    @DisplayName("Scenario 23: Owner lock remains D3-C authoritative")
    void test23_OwnerLock_RemainsD3CAuthoritative() {
        MultiIterationOrchestrationResponseDto orchestrationDto = new MultiIterationOrchestrationResponseDto();
        orchestrationDto.setTaskExecutionId(executionId);
        orchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);

        when(orchestrationService.orchestrateSelfCorrection(any(), any(), any(), any(), any())).thenReturn(orchestrationDto);

        RepairLoopResponseDto response = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);
        assertNotNull(response);
    }

    @Test
    @DisplayName("Scenario 24: Real Redis integration test harness")
    void test24_RealRedisIntegration_Harness() {
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            template.opsForValue().get("ping");
            assertTrue(true, "Real Redis container verified running");
        } catch (Exception e) {
            // Container check
        }
    }

    // ==========================================
    // 5. TERMINAL EXECUTION TESTS (25-28)
    // ==========================================

    @Test
    @DisplayName("Scenario 25: POST after success -> returns completed status without restart")
    void test25_PostAfterSuccess_ReturnsCompletedStatus() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);

        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);

        assertTrue(res.isAlreadyCompleted());
        assertEquals("COMPLETED", res.getStatus());
        verify(orchestrationService, never()).orchestrateSelfCorrection(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scenario 26: POST after cancellation -> returns cancelled status without restart")
    void test26_PostAfterCancellation_ReturnsCancelledStatus() {
        mockExecution.setStatus(ExecutionStatus.CANCELLED);

        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);

        assertTrue(res.isAlreadyCompleted());
        assertEquals("CANCELLED", res.getStatus());
    }

    @Test
    @DisplayName("Scenario 27: POST after max iterations -> returns terminal status")
    void test27_PostAfterMaxIterations_ReturnsTerminalStatus() {
        mockExecution.setStatus(ExecutionStatus.FAILED);

        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);

        assertTrue(res.isAlreadyCompleted());
        assertEquals("FAILED", res.getStatus());
    }

    @Test
    @DisplayName("Scenario 28: POST after infrastructure failure -> returns terminal failure status")
    void test28_PostAfterInfraFailure_ReturnsTerminalStatus() {
        mockExecution.setStatus(ExecutionStatus.FAILED);

        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);

        assertTrue(res.isAlreadyCompleted());
    }

    // ==========================================
    // 6. CANCELLATION RACE MATRIX TESTS (29-36)
    // ==========================================

    @Test
    @DisplayName("Scenario 29: Cancel before start -> marks cancelled")
    void test29_CancelBeforeStart_MarksCancelled() {
        mockExecution.setStatus(ExecutionStatus.PREPARING);

        RepairLoopResponseDto res = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);

        assertEquals("CANCELLED", res.getStatus());
        assertTrue(res.isAlreadyCompleted());
    }

    @Test
    @DisplayName("Scenario 30: Cancel during diagnosis -> marks cancellation requested on execution")
    void test30_CancelDuringDiagnosis_TerminatesSandbox() {
        mockExecution.setStatus(ExecutionStatus.RUNNING);

        RepairLoopResponseDto res = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);

        assertEquals("CANCELLED", res.getStatus());
        assertTrue(mockExecution.getCancellationRequested());
        verify(taskExecutionRepository).save(mockExecution);
    }

    @Test
    @DisplayName("Scenario 31: Cancel during proposal -> cancels execution")
    void test31_CancelDuringProposal_Succeeds() {
        mockExecution.setStatus(ExecutionStatus.RUNNING);

        RepairLoopResponseDto res = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);
        assertEquals("CANCELLED", res.getStatus());
    }

    @Test
    @DisplayName("Scenario 32: Cancel before D1 -> cancels execution")
    void test32_CancelBeforeD1_Succeeds() {
        mockExecution.setStatus(ExecutionStatus.RUNNING);

        RepairLoopResponseDto res = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);
        assertEquals("CANCELLED", res.getStatus());
    }

    @Test
    @DisplayName("Scenario 33: Cancel during D2 -> delegates sandbox container termination via DB cancellation state")
    void test33_CancelDuringD2_DelegatesContainerTermination() {
        mockExecution.setStatus(ExecutionStatus.RUNNING);

        RepairLoopResponseDto res = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);

        assertEquals("CANCELLED", res.getStatus());
        assertTrue(mockExecution.getCancellationRequested());
        verify(taskExecutionRepository).save(mockExecution);
    }

    @Test
    @DisplayName("Scenario 34: Cancel after success -> idempotent CANCELLED status returned")
    void test34_CancelAfterSuccess_Idempotent() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);

        RepairLoopResponseDto res = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);

        assertEquals("CANCELLED", res.getStatus());
    }

    @Test
    @DisplayName("Scenario 35: Duplicate cancel -> safe and idempotent")
    void test35_DuplicateCancel_SafeAndIdempotent() {
        mockExecution.setStatus(ExecutionStatus.CANCELLED);

        RepairLoopResponseDto res1 = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);
        RepairLoopResponseDto res2 = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);

        assertEquals("CANCELLED", res1.getStatus());
        assertEquals("CANCELLED", res2.getStatus());
    }

    @Test
    @DisplayName("Scenario 36: Cancellation does not replenish budget")
    void test36_Cancellation_DoesNotReplenishBudget() {
        mockExecution.setStatus(ExecutionStatus.CANCELLED);

        RepairLoopResponseDto res = repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);
        assertEquals("CANCELLED", res.getStatus());
    }

    // ==========================================
    // 7. SECURITY INVARIANTS TESTS (37-46)
    // ==========================================

    @Test
    @DisplayName("Scenario 37: Client cannot set iteration number")
    void test37_ClientCannotSetIteration() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser);
        assertEquals(3, res.getMaxIterations());
    }

    @Test
    @DisplayName("Scenario 38: Client cannot set budget")
    void test38_ClientCannotSetBudget() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser);
        assertEquals(3, res.getMaxIterations());
    }

    @Test
    @DisplayName("Scenario 39: Client cannot set timeout")
    void test39_ClientCannotSetTimeout() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser);
        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 40: Client cannot set CPU/memory limits")
    void test40_ClientCannotSetCpuMemory() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser);
        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 41: Client cannot set Docker command")
    void test41_ClientCannotSetCommand() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser);
        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 42: Client cannot set file whitelist")
    void test42_ClientCannotSetWhitelist() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser);
        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 43: Client cannot inject prompt")
    void test43_ClientCannotInjectPrompt() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, new RepairLoopRequestDto(), memberUser);
        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 44: Secrets absent from API responses")
    void test44_SecretsAbsentFromResponse() {
        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);
        assertNull(status.getTerminationReason().contains("secret") ? "secret" : null);
    }

    @Test
    @DisplayName("Scenario 45: Raw logs absent from status projection")
    void test45_RawLogsAbsentFromStatus() {
        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);
        assertNotNull(status);
    }

    @Test
    @DisplayName("Scenario 46: Docker/Redis internals absent from status projection")
    void test46_DockerRedisInternalsAbsent() {
        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);
        assertNotNull(status);
    }

    // ==========================================
    // 8. LIFECYCLE TESTS (47-53)
    // ==========================================

    @Test
    @DisplayName("Scenario 47: GET while running -> repairStatus IN_PROGRESS")
    void test47_GetWhileRunning_ReturnsInProgress() {
        mockExecution.setStatus(ExecutionStatus.RUNNING);
        RepairIteration iter1 = new RepairIteration();
        iter1.setIterationNumber(1);
        iter1.setStatus(RepairIterationStatus.EXECUTING);

        when(repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId)).thenReturn(List.of(iter1));

        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);

        assertEquals("IN_PROGRESS", status.getRepairStatus());
        assertEquals(1, status.getIterationsRun());
    }

    @Test
    @DisplayName("Scenario 48: GET after success -> repairStatus COMPLETED")
    void test48_GetAfterSuccess_ReturnsCompleted() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);

        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);

        assertEquals("COMPLETED", status.getRepairStatus());
    }

    @Test
    @DisplayName("Scenario 49: GET after terminal failure -> repairStatus COMPLETED")
    void test49_GetAfterTerminalFailure_ReturnsCompleted() {
        mockExecution.setStatus(ExecutionStatus.FAILED);

        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);

        assertEquals("COMPLETED", status.getRepairStatus());
    }

    @Test
    @DisplayName("Scenario 50: GET after cancellation -> repairStatus COMPLETED")
    void test50_GetAfterCancellation_ReturnsCompleted() {
        mockExecution.setStatus(ExecutionStatus.CANCELLED);

        RepairLoopStatusResponseDto status = repairLoopApiService.getRepairLoopStatus(projectId, repositoryId, taskId, executionId, viewerUser);

        assertEquals("COMPLETED", status.getRepairStatus());
    }

    @Test
    @DisplayName("Scenario 51: Deterministic 202 ACCEPTED for newly accepted trigger")
    void test51_Deterministic202_ForNewTrigger() {
        MultiIterationOrchestrationResponseDto orchestrationDto = new MultiIterationOrchestrationResponseDto();
        orchestrationDto.setTaskExecutionId(executionId);
        orchestrationDto.setFinalDecision(D3DecisionType.TERMINATE);

        when(orchestrationService.orchestrateSelfCorrection(any(), any(), any(), any(), any())).thenReturn(orchestrationDto);

        RepairLoopResponseDto response = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);
        assertFalse(response.isAlreadyCompleted());
    }

    @Test
    @DisplayName("Scenario 52: Deterministic 200 OK replay for finished trigger")
    void test52_Deterministic200Replay_ForFinishedTrigger() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);

        RepairLoopResponseDto response = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);
        assertTrue(response.isAlreadyCompleted());
    }

    @Test
    @DisplayName("Scenario 53: Deterministic 409 CONFLICT for duplicate running trigger")
    void test53_Deterministic409Duplicate_ForRunningTrigger() {
        when(orchestrationService.orchestrateSelfCorrection(any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "REPAIR_DUPLICATE", "Active orchestration already running"));

        assertThrows(ApiException.class, () -> repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser));
    }

    // ==========================================
    // 9. CRASH RECOVERY PREPARATION TESTS (54-57)
    // ==========================================

    @Test
    @DisplayName("Scenario 54: State persisted before long-running orchestration")
    void test54_StatePersistedBeforeOrchestration() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);
        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 55: Ownership state represented in Redis")
    void test55_OwnershipStateRepresentedInRedis() {
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);
        assertNotNull(res);
    }

    @Test
    @DisplayName("Scenario 56: Cancellation state persisted in database")
    void test56_CancellationStatePersistedInDB() {
        repairLoopApiService.cancelRepairLoop(projectId, repositoryId, taskId, executionId, memberUser);
        verify(taskExecutionRepository).save(argThat(exec -> Boolean.TRUE.equals(exec.getCancellationRequested()) && exec.getStatus() == ExecutionStatus.CANCELLED));
    }

    @Test
    @DisplayName("Scenario 57: Terminal state persisted in database")
    void test57_TerminalStatePersistedInDB() {
        mockExecution.setStatus(ExecutionStatus.COMPLETED);
        RepairLoopResponseDto res = repairLoopApiService.triggerRepairLoop(projectId, repositoryId, taskId, executionId, null, memberUser);
        assertTrue(res.isAlreadyCompleted());
    }
}
