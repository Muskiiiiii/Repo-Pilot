package com.repopilot.testing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.dto.*;
import com.repopilot.testing.entity.RepairIteration;
import com.repopilot.testing.entity.RepairLoopIdempotencyRecord;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.RepairLoopIdempotencyRecordRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RepairLoopApiServiceImpl implements RepairLoopApiService {

    private static final Logger logger = LoggerFactory.getLogger(RepairLoopApiServiceImpl.class);

    private final TaskExecutionRepository taskExecutionRepository;
    private final RepairIterationRepository repairIterationRepository;
    private final ProjectAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final MultiIterationRepairOrchestrationService orchestrationService;
    private final RepairLoopIdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public RepairLoopApiServiceImpl(TaskExecutionRepository taskExecutionRepository,
                                    RepairIterationRepository repairIterationRepository,
                                    ProjectAuthorizationService authorizationService,
                                    AuditLogService auditLogService,
                                    MultiIterationRepairOrchestrationService orchestrationService,
                                    RepairLoopIdempotencyRecordRepository idempotencyRecordRepository,
                                    ObjectMapper objectMapper) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.repairIterationRepository = repairIterationRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.orchestrationService = orchestrationService;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.objectMapper = objectMapper != null ? objectMapper.findAndRegisterModules() : new ObjectMapper().findAndRegisterModules();
    }

    @Override
    @Transactional
    public RepairLoopResponseDto triggerRepairLoop(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, RepairLoopRequestDto request, User currentUser) {
        // 1. Authorization & Role Verification (MEMBER required)
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        // 2. Strict IDOR Context Query
        TaskExecution execution = taskExecutionRepository
                .findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found for context"));

        String idempotencyKey = (request != null && request.getIdempotencyKey() != null) ? request.getIdempotencyKey().trim() : null;

        // Input Sanity Validation
        if (idempotencyKey != null && idempotencyKey.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency key exceeds maximum length of 128 characters");
        }

        // 3. Durable Idempotency Lookup (PostgreSQL-backed)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<RepairLoopIdempotencyRecord> existingRecordOpt = idempotencyRecordRepository
                    .findByUserIdAndProjectIdAndExecutionIdAndOperationAndIdempotencyKey(
                            currentUser.getId(), projectId, executionId, "REPAIR_ORCHESTRATION", idempotencyKey);

            if (existingRecordOpt.isPresent()) {
                RepairLoopIdempotencyRecord record = existingRecordOpt.get();
                try {
                    RepairLoopResponseDto cachedResponse = objectMapper.readValue(record.getResponsePayload(), RepairLoopResponseDto.class);
                    cachedResponse.setAlreadyCompleted(true);
                    logger.info("Returning durable idempotency response for execution {} key {}", executionId, idempotencyKey);
                    return cachedResponse;
                } catch (Exception e) {
                    logger.warn("Failed to deserialize idempotency record payload for execution {}: {}", executionId, e.getMessage());
                }
            }
        }

        // 4. Terminal State Replay Defense
        if (isTerminalStatus(execution.getStatus())) {
            List<RepairIteration> iterations = repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId);
            RepairLoopResponseDto terminalDto = new RepairLoopResponseDto();
            terminalDto.setExecutionId(executionId);
            terminalDto.setTaskId(taskId);
            terminalDto.setStatus(execution.getStatus().name());
            terminalDto.setIterationsRun(iterations.size());
            terminalDto.setMaxIterations(3);
            terminalDto.setOutcomePolicy(execution.getStatus() == ExecutionStatus.COMPLETED ? "REPAIR_SUCCESS" : "TERMINATE");
            terminalDto.setTerminationReason("Execution already reached terminal status " + execution.getStatus());
            terminalDto.setAlreadyCompleted(true);
            terminalDto.setStartedAt(execution.getStartedAt());
            terminalDto.setCompletedAt(execution.getCompletedAt() != null ? execution.getCompletedAt() : Instant.now());
            return terminalDto;
        }

        // 5. Delegate strictly to Phase 10.4-D3-C Orchestration
        MultiIterationOrchestrationResponseDto orchestrationDto = orchestrationService.orchestrateSelfCorrection(
                projectId, repositoryId, taskId, executionId, currentUser);

        RepairLoopResponseDto response = new RepairLoopResponseDto();
        response.setExecutionId(orchestrationDto.getTaskExecutionId());
        response.setTaskId(taskId);
        response.setStatus(orchestrationDto.getFinalDecision() != null ? orchestrationDto.getFinalDecision().name() : "TERMINATED");
        response.setIterationsRun(orchestrationDto.getIterationsRun());
        response.setMaxIterations(3);
        response.setOutcomePolicy(orchestrationDto.getOutcomePolicy());
        response.setTerminationReason(orchestrationDto.getFinalReason());
        response.setAlreadyCompleted(false);
        response.setStartedAt(execution.getStartedAt());
        response.setCompletedAt(orchestrationDto.getCompletedAt());

        // 6. Save Durable Idempotency Record (if idempotency key present)
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                String payloadJson = objectMapper.writeValueAsString(response);
                RepairLoopIdempotencyRecord newRecord = new RepairLoopIdempotencyRecord(
                        currentUser.getId(), projectId, executionId, "REPAIR_ORCHESTRATION", idempotencyKey, 200, payloadJson);
                idempotencyRecordRepository.save(newRecord);
            } catch (Exception e) {
                logger.warn("Failed to persist idempotency record for execution {}: {}", executionId, e.getMessage());
            }
        }

        String outcomePolicy = orchestrationDto.getOutcomePolicy() != null ? orchestrationDto.getOutcomePolicy() : "TERMINATE";
        auditLogService.logAction(currentUser, project, execution.getTask(), "REPAIR_ORCHESTRATION_TRIGGERED", "TASK_EXECUTION", executionId,
                toJson(Map.of("iterationsRun", String.valueOf(orchestrationDto.getIterationsRun()), "outcomePolicy", outcomePolicy)));

        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public RepairLoopStatusResponseDto getRepairLoopStatus(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser) {
        // 1. Authorization & Role Verification (VIEWER required)
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.VIEWER);

        // 2. Strict IDOR Context Query
        TaskExecution execution = taskExecutionRepository
                .findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found for context"));

        List<RepairIteration> iterations = repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId);
        List<RepairIterationSummaryDto> iterationSummaries = iterations.stream()
                .map(this::buildIterationSummary)
                .collect(Collectors.toList());

        RepairLoopStatusResponseDto statusDto = new RepairLoopStatusResponseDto();
        statusDto.setExecutionId(executionId);
        statusDto.setTaskId(taskId);
        statusDto.setExecutionStatus(execution.getStatus() != null ? execution.getStatus().name() : "UNKNOWN");
        statusDto.setRepairStatus(isTerminalStatus(execution.getStatus()) ? "COMPLETED" : (iterations.isEmpty() ? "NOT_STARTED" : "IN_PROGRESS"));
        statusDto.setIterationsRun(iterations.size());
        statusDto.setMaxIterations(3);
        statusDto.setLatestDecision(!iterations.isEmpty() ? iterations.get(iterations.size() - 1).getStatus().name() : "NONE");
        statusDto.setOutcomePolicy(execution.getStatus() == ExecutionStatus.COMPLETED ? "REPAIR_SUCCESS" : "IN_PROGRESS");
        statusDto.setTerminationReason("Status query clean projection");
        statusDto.setIterations(iterationSummaries);
        statusDto.setStartedAt(execution.getStartedAt());
        statusDto.setCompletedAt(execution.getCompletedAt());

        return statusDto;
    }

    @Override
    @Transactional
    public RepairLoopResponseDto cancelRepairLoop(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser) {
        // 1. Authorization & Role Verification (MEMBER required)
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        // 2. Strict IDOR Context Query
        TaskExecution execution = taskExecutionRepository
                .findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found for context"));

        List<RepairIteration> iterations = repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId);

        // 3. Race-Safe Cancellation Application using Atomic Query
        int cancelled = taskExecutionRepository.requestCancellationAtomic(executionId, Instant.now());
        if (cancelled > 0) {
            execution = taskExecutionRepository.findById(executionId).orElse(execution);
            execution.setCancellationRequested(true);
            execution.setStatus(ExecutionStatus.CANCELLED);
            auditLogService.logAction(currentUser, project, execution.getTask(), "REPAIR_ORCHESTRATION_CANCELLED", "TASK_EXECUTION", executionId, null);
        } else if (!isTerminalStatus(execution.getStatus())) {
            execution.setCancellationRequested(true);
            execution.setStatus(ExecutionStatus.CANCELLED);
            taskExecutionRepository.save(execution);
            auditLogService.logAction(currentUser, project, execution.getTask(), "REPAIR_ORCHESTRATION_CANCELLED", "TASK_EXECUTION", executionId, null);
        } else {
            logger.warn("Cancellation request skipped or fenced out (execution terminal) for {}", executionId);
            execution = taskExecutionRepository.findById(executionId).orElse(execution);
        }

        RepairLoopResponseDto response = new RepairLoopResponseDto();
        response.setExecutionId(executionId);
        response.setTaskId(taskId);
        response.setStatus("CANCELLED");
        response.setIterationsRun(iterations.size());
        response.setMaxIterations(3);
        response.setOutcomePolicy("TERMINATE");
        response.setTerminationReason("Repair loop orchestration cancelled by authorized user");
        response.setAlreadyCompleted(true);
        response.setStartedAt(execution.getStartedAt());
        response.setCompletedAt(execution.getCompletedAt() != null ? execution.getCompletedAt() : Instant.now());

        return response;
    }

    private RepairIterationSummaryDto buildIterationSummary(RepairIteration iter) {
        RepairIterationSummaryDto dto = new RepairIterationSummaryDto();
        dto.setIterationId(iter.getId());
        dto.setIterationNumber(iter.getIterationNumber());
        dto.setStatus(iter.getStatus() != null ? iter.getStatus().name() : "UNKNOWN");
        dto.setEligibilityStatus(iter.getStatus() != null ? iter.getStatus().name() : "VALIDATED");
        dto.setDiagnosisStatus(iter.getStatus() != null ? iter.getStatus().name() : "VALIDATED");
        dto.setProposalStatus(iter.getStatus() != null ? iter.getStatus().name() : "VALIDATED");
        dto.setPhysicalApplicationStatus(iter.getStatus() != null ? iter.getStatus().name() : "VALIDATED");
        dto.setRetestStatus(iter.getStatus() != null ? iter.getStatus().name() : "PASSED");
        dto.setFailureCategory(iter.getFailureCategory() != null ? iter.getFailureCategory().name() : null);
        dto.setRepairability(iter.getRepairability() != null ? iter.getRepairability().name() : null);
        dto.setStartedAt(iter.getCreatedAt());
        dto.setCompletedAt(iter.getCompletedAt());
        return dto;
    }

    private boolean isTerminalStatus(ExecutionStatus status) {
        return status == ExecutionStatus.COMPLETED ||
               status == ExecutionStatus.CANCELLED ||
               status == ExecutionStatus.FAILED ||
               status == ExecutionStatus.TIMED_OUT;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
