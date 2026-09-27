package com.repopilot.testing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.entity.TaskStatus;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.dto.DockerExecutionResult;
import com.repopilot.testing.dto.TaskExecutionRequestDto;
import com.repopilot.testing.dto.TaskExecutionResponseDto;
import com.repopilot.testing.dto.TestRunResponseDto;
import com.repopilot.testing.entity.Ecosystem;
import com.repopilot.testing.entity.FailureType;
import com.repopilot.testing.entity.TestRun;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.user.entity.User;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.parser.TestResultParserCompositeService;
import com.repopilot.testing.dto.FailureClassification;
import com.repopilot.testing.service.FailureClassifierService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskExecutionServiceImpl implements TaskExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutionServiceImpl.class);
    private static final Map<UUID, String> activeExecutionLocks = new ConcurrentHashMap<>();

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    private final ProjectAuthorizationService authorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final TaskExecutionRepository taskExecutionRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final TestRunRepository testRunRepository;
    private final WorkspaceManagerService workspaceManagerService;
    private final DockerSandboxExecutorService dockerSandboxExecutorService;
    private final TestResultParserCompositeService testResultParserCompositeService;
    private final FailureClassifierService failureClassifierService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskExecutionServiceImpl(ProjectAuthorizationService authorizationService,
                                   RepositoryRepository repositoryRepository,
                                   TaskRepository taskRepository,
                                   TaskExecutionRepository taskExecutionRepository,
                                   CodeChangeRepository codeChangeRepository,
                                   TestRunRepository testRunRepository,
                                   WorkspaceManagerService workspaceManagerService,
                                   DockerSandboxExecutorService dockerSandboxExecutorService,
                                   TestResultParserCompositeService testResultParserCompositeService,
                                   FailureClassifierService failureClassifierService,
                                   AuditLogService auditLogService,
                                   ObjectMapper objectMapper) {
        this.authorizationService = authorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.taskExecutionRepository = taskExecutionRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.testRunRepository = testRunRepository;
        this.workspaceManagerService = workspaceManagerService;
        this.dockerSandboxExecutorService = dockerSandboxExecutorService;
        this.testResultParserCompositeService = testResultParserCompositeService;
        this.failureClassifierService = failureClassifierService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskExecutionResponseDto executeTask(UUID projectId, UUID repositoryId, UUID taskId, TaskExecutionRequestDto request, User currentUser) {
        // 1. Authorization & Entity Resolution
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found"));

        if (!repository.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Repository does not belong to specified project");
        }

        Task task = taskRepository.findByIdAndProjectIdAndRepositoryId(taskId, projectId, repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found in specified project/repository"));

        String testTarget = request != null ? request.getTestTarget() : null;
        validateTestTargetSecurity(testTarget);

        // 2. Persistent DB Concurrency Ownership Lock
        boolean activeExists = taskExecutionRepository.existsByTaskIdAndStatusIn(taskId, List.of(ExecutionStatus.PREPARING, ExecutionStatus.RUNNING));
        if (activeExists) {
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_IN_PROGRESS", "An execution is already running for task " + taskId);
        }

        UUID executionId = UUID.randomUUID();
        String executionToken = UUID.randomUUID().toString();
        Ecosystem ecosystem = request != null && request.getEcosystem() != null ? request.getEcosystem() : Ecosystem.PYTHON;

        TaskExecution execution = new TaskExecution();
        execution.setId(executionId);
        execution.setTask(task);
        execution.setRepository(repository);
        execution.setProject(project);
        execution.setExecutionToken(executionToken);
        execution.setEcosystem(ecosystem);
        execution.setStatus(ExecutionStatus.PREPARING);

        // Calculate execution number
        Integer nextExecutionNum = taskExecutionRepository.findFirstByTaskIdOrderByExecutionNumberDesc(taskId)
                .map(e -> e.getExecutionNumber() + 1).orElse(1);
        execution.setExecutionNumber(nextExecutionNum);

        // 3. Freeze & Capture CodeChange Snapshot
        List<CodeChange> codeChanges = codeChangeRepository.findByTaskId(taskId);
        try {
            String snapshotJson = objectMapper.writeValueAsString(codeChanges);
            execution.setCodeChangeSnapshot(snapshotJson);
        } catch (Exception e) {
            logger.warn("Failed to serialize code change snapshot: {}", e.getMessage());
        }

        try {
            execution = taskExecutionRepository.save(execution);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            logger.warn("Concurrent execution conflict for task {}: {}", taskId, e.getMessage());
            throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_IN_PROGRESS", "An execution is already running for task " + taskId);
        }

        task.setStatus(TaskStatus.TESTING);
        taskRepository.save(task);

        auditLogService.logAction(currentUser, project, task, "EXECUTION_STARTED", "TASK_EXECUTION", executionId,
                toJson(Map.of("ecosystem", ecosystem.name(), "executionNumber", nextExecutionNum)));

        // 4. Isolated Execution Pipeline (outside long DB transaction)
        Path workspaceRepoDir = null;
        DockerExecutionResult execResult = null;
        String masterDigestBefore = "";
        String masterDigestAfter = "";
        List<CodeChange> snapshotToApply = codeChanges;

        try {
            // Materialize physical workspace
            masterDigestBefore = workspaceManagerService.calculateTreeDigest(Paths.get(baseStoragePath, repositoryId.toString()));
            workspaceRepoDir = workspaceManagerService.materializeWorkspace(executionId, repository);

            // Apply Frozen CodeChange Snapshot (Snapshot Isolation)
            if (execution.getCodeChangeSnapshot() != null && !execution.getCodeChangeSnapshot().isBlank()) {
                try {
                    snapshotToApply = objectMapper.readValue(execution.getCodeChangeSnapshot(), new TypeReference<List<CodeChange>>() {});
                } catch (Exception e) {
                    logger.warn("Failed to deserialize snapshot for execution {}, falling back to query: {}", executionId, e.getMessage());
                }
            }

            workspaceManagerService.applyCodeChangeSnapshot(executionId, workspaceRepoDir, snapshotToApply);

            // Execute Sandbox Container
            execution.setStatus(ExecutionStatus.RUNNING);
            taskExecutionRepository.save(execution);

            execResult = dockerSandboxExecutorService.executeSandboxTest(executionId, taskId, ecosystem, testTarget, workspaceRepoDir, Duration.ofSeconds(60));

            // Verify Master Repo Immutability
            masterDigestAfter = workspaceManagerService.calculateTreeDigest(Paths.get(baseStoragePath, repositoryId.toString()));
            if (!masterDigestBefore.isBlank() && !masterDigestBefore.equalsIgnoreCase(masterDigestAfter)) {
                logger.error("CRITICAL SECURITY INVARIANT VIOLATION: Master repository SHA-256 tree digest changed during execution {}!", executionId);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SECURITY_INVARIANT_VIOLATION", "Master repository content was mutated during execution");
            }

        } catch (Exception e) {
            logger.error("Execution error for execution {}: {}", executionId, e.getMessage(), e);
            execution.setStatus(ExecutionStatus.ERROR);
            execution.setErrorMessage(e.getMessage());
            execution.setCompletedAt(Instant.now());
            taskExecutionRepository.save(execution);
        } finally {
            // Clean up workspace & container
            if (execResult != null && execResult.getContainerId() != null) {
                dockerSandboxExecutorService.terminateContainer(execResult.getContainerId());
            }
            workspaceManagerService.cleanupWorkspace(executionId);
            execution.setCleanedAt(Instant.now());
        }

        // 5. Persist Test Run & Finalize Execution Status
        TestRun testRun = new TestRun();
        testRun.setTask(task);
        testRun.setTaskExecution(execution);
        testRun.setExecutionNumber(nextExecutionNum);
        testRun.setEcosystem(ecosystem);
        testRun.setTestTarget(testTarget);

        if (execResult != null) {
            testRun.setCommand(execResult.getCommandExecuted());
            testRun.setContainerId(execResult.getContainerId());
            testRun.setExitCode(execResult.getExitCode());
            testRun.setDurationMs(execResult.getDurationMs());
            testRun.setFailureType(execResult.getFailureType());
            testRun.setStdoutSummary(execResult.getStdout());
            testRun.setStderrSummary(execResult.getStderr());
            testRun.setStdoutTruncated(execResult.isStdoutTruncated());
            testRun.setStderrTruncated(execResult.isStderrTruncated());
            testRun.setTerminationReason(execResult.getTerminationReason());
            testRun.setStdoutBytesObserved(execResult.getStdoutBytesObserved());
            testRun.setStderrBytesObserved(execResult.getStderrBytesObserved());

            ParsedTestEvidence parsedEvidence = testResultParserCompositeService.parse(ecosystem, execResult.getStdout(), execResult.getStderr());
            testRun.setTestsRun(parsedEvidence.getTestsRun());
            testRun.setFailuresCount(parsedEvidence.getFailuresCount());
            testRun.setErrorsCount(parsedEvidence.getErrorsCount());
            testRun.setSkippedCount(parsedEvidence.getSkippedCount());
            testRun.setPassedCount(parsedEvidence.getPassedCount());
            testRun.setParserStatus(parsedEvidence.getParserStatus());
            testRun.setFailureEvidence(toJson(parsedEvidence.getMetadata()));

            if (execResult.getExitCode() != null && execResult.getExitCode() == 0) {
                testRun.setStatus("PASSED");
                execution.setStatus(ExecutionStatus.COMPLETED);
            } else if (execResult.getFailureType() == FailureType.TIMEOUT) {
                testRun.setStatus("TIMED_OUT");
                execution.setStatus(ExecutionStatus.TIMED_OUT);
            } else {
                testRun.setStatus("FAILED");
                execution.setStatus(ExecutionStatus.FAILED);
            }
        } else {
            testRun.setStatus("ERROR");
            testRun.setFailureType(FailureType.EXECUTION_INFRA_FAILURE);
            testRun.setTerminationReason(com.repopilot.testing.entity.TerminationReason.EXECUTION_INFRA_FAILURE);
            execution.setStatus(ExecutionStatus.FAILED);
        }

        // Perform Deterministic Failure Classification (Phase 10.3)
        FailureClassification classification = failureClassifierService.classify(ecosystem, execResult, snapshotToApply);
        testRun.setFailureCategory(classification.getFailureCategory());
        testRun.setRepairability(classification.getRepairability());
        testRun.setConfidence(classification.getConfidence());
        testRun.setClassificationReason(classification.getReason());
        testRun.setClassificationEvidence(toJson(classification.getEvidence()));
        testRun.setClassifiedAt(classification.getClassifiedAt());
        testRun.setClassifierVersion(classification.getClassifierVersion());

        testRun.setCompletedAt(Instant.now());
        TestRun savedTestRun = testRunRepository.save(testRun);
        if (savedTestRun != null) {
            testRun = savedTestRun;
        }

        execution.setCompletedAt(Instant.now());
        execution = taskExecutionRepository.save(execution);

        Integer exitCodeVal = (testRun != null && testRun.getExitCode() != null) ? testRun.getExitCode() : -1;
        auditLogService.logAction(currentUser, project, task, "EXECUTION_COMPLETED", "TASK_EXECUTION", executionId,
                toJson(Map.of("status", execution.getStatus().name(), "exitCode", exitCodeVal)));

        return buildResponseDto(execution, testRun);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskExecutionResponseDto getExecutionStatus(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.VIEWER);

        TaskExecution execution = taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found"));

        List<TestRun> testRuns = testRunRepository.findByTaskExecutionId(executionId);
        TestRun latestRun = !testRuns.isEmpty() ? testRuns.get(testRuns.size() - 1) : null;

        return buildResponseDto(execution, latestRun);
    }

    @Override
    public TaskExecutionResponseDto cancelExecution(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        TaskExecution execution = taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found"));

        if (execution.getStatus() == ExecutionStatus.RUNNING || execution.getStatus() == ExecutionStatus.PREPARING) {
            execution.setCancellationRequested(true);
            execution.setStatus(ExecutionStatus.CANCELLED);
            execution.setCompletedAt(Instant.now());
            execution = taskExecutionRepository.save(execution);

            dockerSandboxExecutorService.terminateContainer("repopilot_exec_" + executionId);
            workspaceManagerService.cleanupWorkspace(executionId);

            auditLogService.logAction(currentUser, project, execution.getTask(), "EXECUTION_CANCELLED", "TASK_EXECUTION", executionId, null);
        }

        List<TestRun> testRuns = testRunRepository.findByTaskExecutionId(executionId);
        TestRun latestRun = !testRuns.isEmpty() ? testRuns.get(testRuns.size() - 1) : null;

        return buildResponseDto(execution, latestRun);
    }

    private TaskExecutionResponseDto buildResponseDto(TaskExecution execution, TestRun testRun) {
        TaskExecutionResponseDto dto = new TaskExecutionResponseDto();
        dto.setExecutionId(execution.getId());
        dto.setTaskId(execution.getTask().getId());
        dto.setRepositoryId(execution.getRepository() != null ? execution.getRepository().getId() : null);
        dto.setProjectId(execution.getProject() != null ? execution.getProject().getId() : null);
        dto.setExecutionNumber(execution.getExecutionNumber());
        dto.setStatus(execution.getStatus());
        dto.setEcosystem(execution.getEcosystem());
        dto.setCancellationRequested(execution.getCancellationRequested());
        dto.setStartedAt(execution.getStartedAt());
        dto.setCompletedAt(execution.getCompletedAt());
        dto.setCleanedAt(execution.getCleanedAt());
        dto.setErrorMessage(execution.getErrorMessage());

        if (testRun != null) {
            TestRunResponseDto trDto = new TestRunResponseDto();
            trDto.setId(testRun.getId());
            trDto.setTaskId(testRun.getTask().getId());
            trDto.setExecutionId(execution.getId());
            trDto.setExecutionNumber(testRun.getExecutionNumber());
            trDto.setEcosystem(testRun.getEcosystem());
            trDto.setTestTarget(testRun.getTestTarget());
            trDto.setCommand(testRun.getCommand());
            trDto.setStatus(testRun.getStatus());
            trDto.setFailureType(testRun.getFailureType());
            trDto.setExitCode(testRun.getExitCode());
            trDto.setDurationMs(testRun.getDurationMs());
            trDto.setStdoutSummary(testRun.getStdoutSummary());
            trDto.setStderrSummary(testRun.getStderrSummary());
            trDto.setStdoutTruncated(testRun.getStdoutTruncated());
            trDto.setStderrTruncated(testRun.getStderrTruncated());
            trDto.setTerminationReason(testRun.getTerminationReason());
            trDto.setStdoutBytesObserved(testRun.getStdoutBytesObserved());
            trDto.setStderrBytesObserved(testRun.getStderrBytesObserved());
            trDto.setTestsRun(testRun.getTestsRun());
            trDto.setFailuresCount(testRun.getFailuresCount());
            trDto.setErrorsCount(testRun.getErrorsCount());
            trDto.setSkippedCount(testRun.getSkippedCount());
            trDto.setPassedCount(testRun.getPassedCount());
            trDto.setParserStatus(testRun.getParserStatus());
            if (testRun.getFailureEvidence() != null && !testRun.getFailureEvidence().isBlank()) {
                try {
                    trDto.setFailureEvidence(objectMapper.readValue(testRun.getFailureEvidence(), Map.class));
                } catch (Exception e) {
                    trDto.setFailureEvidence(testRun.getFailureEvidence());
                }
            }
            trDto.setFailureCategory(testRun.getFailureCategory());
            trDto.setRepairability(testRun.getRepairability());
            trDto.setConfidence(testRun.getConfidence());
            trDto.setClassificationReason(testRun.getClassificationReason());
            trDto.setClassifierVersion(testRun.getClassifierVersion());
            trDto.setClassifiedAt(testRun.getClassifiedAt());
            if (testRun.getClassificationEvidence() != null && !testRun.getClassificationEvidence().isBlank()) {
                try {
                    trDto.setClassificationEvidence(objectMapper.readValue(testRun.getClassificationEvidence(), Map.class));
                } catch (Exception e) {
                    trDto.setClassificationEvidence(testRun.getClassificationEvidence());
                }
            }
            trDto.setStartedAt(testRun.getStartedAt());
            trDto.setCompletedAt(testRun.getCompletedAt());
            dto.setTestRun(trDto);
        }

        return dto;
    }

    private void validateTestTargetSecurity(String testTarget) {
        if (testTarget == null || testTarget.isBlank()) return;
        if (testTarget.startsWith("/") || testTarget.startsWith("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Test target cannot be an absolute path");
        }
        if (testTarget.contains("\0")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEST_TARGET", "Test target contains null byte");
        }
        if (!testTarget.matches("^[a-zA-Z0-9_/\\.-]+$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEST_TARGET", "Test target contains forbidden shell characters");
        }
        if (testTarget.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PATH_TRAVERSAL", "Test target contains forbidden '..' traversal sequence");
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            logger.warn("Failed to serialize object to JSON string: {}", e.getMessage());
            return null;
        }
    }
}
