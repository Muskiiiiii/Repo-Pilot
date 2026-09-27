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
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.testing.dto.*;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.parser.TestResultParserCompositeService;
import com.repopilot.testing.policy.RepairPolicyValidator;
import com.repopilot.testing.repository.AIRepairProposalRepository;
import com.repopilot.testing.repository.RepairIterationRepository;
import com.repopilot.testing.repository.TestRunRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class SingleIterationRepairExecutionServiceImpl implements SingleIterationRepairExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(SingleIterationRepairExecutionServiceImpl.class);
    private static final Map<UUID, String> activeExecutionLocks = new ConcurrentHashMap<>();

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    private final TaskExecutionRepository taskExecutionRepository;
    private final RepairIterationRepository repairIterationRepository;
    private final AIRepairProposalRepository aiRepairProposalRepository;
    private final TestRunRepository testRunRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final ProjectAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final PhysicalRepairApplicationService physicalRepairApplicationService;
    private final DockerSandboxExecutorService dockerSandboxExecutorService;
    private final TestResultParserCompositeService testResultParserCompositeService;
    private final FailureClassifierService failureClassifierService;
    private final RepairPolicyValidator repairPolicyValidator;
    private final WorkspaceManagerService workspaceManagerService;
    private final ObjectMapper objectMapper;

    @Autowired
    public SingleIterationRepairExecutionServiceImpl(TaskExecutionRepository taskExecutionRepository,
                                                     RepairIterationRepository repairIterationRepository,
                                                     AIRepairProposalRepository aiRepairProposalRepository,
                                                     TestRunRepository testRunRepository,
                                                     CodeChangeRepository codeChangeRepository,
                                                     ProjectAuthorizationService authorizationService,
                                                     AuditLogService auditLogService,
                                                     PhysicalRepairApplicationService physicalRepairApplicationService,
                                                     DockerSandboxExecutorService dockerSandboxExecutorService,
                                                     TestResultParserCompositeService testResultParserCompositeService,
                                                     FailureClassifierService failureClassifierService,
                                                     RepairPolicyValidator repairPolicyValidator,
                                                     WorkspaceManagerService workspaceManagerService,
                                                     ObjectMapper objectMapper) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.repairIterationRepository = repairIterationRepository;
        this.aiRepairProposalRepository = aiRepairProposalRepository;
        this.testRunRepository = testRunRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.physicalRepairApplicationService = physicalRepairApplicationService;
        this.dockerSandboxExecutorService = dockerSandboxExecutorService;
        this.testResultParserCompositeService = testResultParserCompositeService;
        this.failureClassifierService = failureClassifierService;
        this.repairPolicyValidator = repairPolicyValidator;
        this.workspaceManagerService = workspaceManagerService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public RepairExecutionResponseDto executeRepairIteration(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser) {
        // 1. Authorization Verification
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        // 2. Entity Resolution & Context Validation
        TaskExecution execution = taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found for given context"));

        Task task = execution.getTask();
        if (task == null || !task.getId().equals(taskId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_MISMATCH", "Execution does not belong to specified task");
        }

        RepairIteration iteration = repairIterationRepository.findById(iterationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ITERATION_NOT_FOUND", "Repair iteration not found"));

        if (!iteration.getTaskExecution().getId().equals(executionId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ITERATION_MISMATCH", "Repair iteration does not belong to execution " + executionId);
        }

        AIRepairProposal proposal = aiRepairProposalRepository.findByRepairIterationId(iterationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "AIRepairProposal not found for iteration " + iterationId));

        // 3. Idempotency Check: Proposal can be consumed at most once for D2
        if (iteration.getStatus().isTerminal()) {
            logger.info("Repair iteration {} already in terminal state {}. Returning existing result (Idempotent).", iterationId, iteration.getStatus());
            List<TestRun> runs = testRunRepository.findByTaskExecutionId(executionId);
            TestRun latestRun = !runs.isEmpty() ? runs.get(runs.size() - 1) : null;
            String policy = determineOutcomePolicyFromTerminalState(iteration.getStatus(), latestRun);
            return buildResponseDto(iteration, proposal, latestRun, policy);
        }

        // 4. Concurrent D2 Execution Guard
        String existingLock = activeExecutionLocks.putIfAbsent(iterationId, executionId.toString());
        if (existingLock != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_EXECUTION", "Repair execution is already running for iteration " + iterationId);
        }

        UUID disposableExecId = UUID.randomUUID();
        DockerExecutionResult execResult = null;

        try {
            // 5. Pre-Execution Cancellation Check
            if (isCancelled(execution)) {
                return handleCancellation(iteration, execution, currentUser, project, task);
            }

            // 6. Master Repository Baseline Immutability Check (Before)
            Path masterRepoDir = Paths.get(baseStoragePath, repositoryId.toString());
            String masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

            // 7. Physical Repair Application via Phase 10.4-D1
            AIRepairProposalResponseDto d1Result = physicalRepairApplicationService.applyPhysicalRepair(
                    projectId, repositoryId, taskId, executionId, iterationId, currentUser
            );

            if ("STALE_REPAIR_PROPOSAL".equalsIgnoreCase(d1Result.getValidationStatus())) {
                return buildResponseDto(iteration, proposal, null, "REPAIR_STALE");
            }

            if (!"PHYSICALLY_APPLIED".equalsIgnoreCase(d1Result.getValidationStatus()) && !"VALIDATED".equalsIgnoreCase(d1Result.getValidationStatus())) {
                return buildResponseDto(iteration, proposal, null, "REPAIR_SECURITY_REJECTED");
            }

            // Re-fetch updated proposal and iteration after D1 application
            proposal = aiRepairProposalRepository.findByRepairIterationId(iterationId).orElse(proposal);
            iteration = repairIterationRepository.findById(iterationId).orElse(iteration);

            // 8. Re-Materialize Physical Disposable Workspace for Container Rerun
            Path workspaceRepoDir = workspaceManagerService.materializeWorkspace(disposableExecId, execution.getRepository());
            List<CodeChange> approvedCodeChanges = getApprovedCodeChanges(execution, taskId);
            workspaceManagerService.applyCodeChangeSnapshot(disposableExecId, workspaceRepoDir, approvedCodeChanges);

            // Physically apply candidate proposal modifications onto disposable workspace
            applyProposalModificationsToWorkspace(proposal, workspaceRepoDir);

            // 9. Pre-Execution Scope & Anti-Test-Tampering Verification
            String activeResultingTreeHash = workspaceManagerService.calculateTreeDigest(workspaceRepoDir);
            if (proposal.getResultingTreeHash() != null && !activeResultingTreeHash.equalsIgnoreCase(proposal.getResultingTreeHash())) {
                logger.error("SECURITY INVARIANT VIOLATION: Post-application resulting tree hash ({}) does not match expected proposal tree hash ({})",
                        activeResultingTreeHash, proposal.getResultingTreeHash());

                proposal.setValidationStatus("RESULTING_TREE_HASH_MISMATCH");
                aiRepairProposalRepository.save(proposal);

                iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                iteration.setRejectionReason("RESULTING_TREE_HASH_MISMATCH: Tree hash mismatch prior to sandbox execution");
                iteration.setCompletedAt(Instant.now());
                repairIterationRepository.save(iteration);

                workspaceManagerService.cleanupWorkspace(disposableExecId);
                return buildResponseDto(iteration, proposal, null, "REPAIR_SECURITY_REJECTED");
            }

            // 10. Freeze Workspace & Update Iteration Status to EXECUTING
            iteration.setStatus(RepairIterationStatus.EXECUTING);
            repairIterationRepository.save(iteration);

            // Pre-Container Launch Cancellation Check
            if (isCancelled(execution)) {
                workspaceManagerService.cleanupWorkspace(disposableExecId);
                return handleCancellation(iteration, execution, currentUser, project, task);
            }

            // 11. Execute Sandbox Retest Container (Phase 10.1 & 10.2)
            List<TestRun> previousRuns = testRunRepository.findByTaskExecutionId(executionId);
            TestRun baselineTestRun = !previousRuns.isEmpty() ? previousRuns.get(0) : null;
            String testTarget = baselineTestRun != null ? baselineTestRun.getTestTarget() : null;
            Ecosystem ecosystem = execution.getEcosystem() != null ? execution.getEcosystem() : Ecosystem.PYTHON;

            logger.info("Executing D2 single-iteration sandbox test rerun for iteration {} in ecosystem {}", iterationId, ecosystem);

            execResult = dockerSandboxExecutorService.executeSandboxTest(
                    disposableExecId, taskId, ecosystem, testTarget, workspaceRepoDir, Duration.ofSeconds(60)
            );

            // 12. Post-Container Master & Workspace Immutability Verification
            String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
            if (!masterDigestBefore.isBlank() && !masterDigestBefore.equals(masterDigestAfter)) {
                logger.error("CRITICAL SECURITY INVARIANT VIOLATION: Master repository storage mutated during D2 execution!");
                workspaceManagerService.cleanupWorkspace(disposableExecId);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SECURITY_INVARIANT_VIOLATION", "Master repository baseline content was mutated during container sandbox execution");
            }

            // Check if untrusted container process mutated test files or files outside proposal
            if (hasUntrustedWorkspaceMutation(workspaceRepoDir, proposal, approvedCodeChanges)) {
                logger.error("SECURITY INVARIANT VIOLATION: Untrusted container process mutated forbidden files inside mounted workspace!");
                iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                iteration.setRejectionReason("SECURITY_INVARIANT_VIOLATION: Untrusted container process mutated workspace files");
                iteration.setCompletedAt(Instant.now());
                repairIterationRepository.save(iteration);

                workspaceManagerService.cleanupWorkspace(disposableExecId);
                return buildResponseDto(iteration, proposal, null, "REPAIR_SECURITY_REJECTED");
            }

            // Post-Container Cancellation Check
            if (isCancelled(execution)) {
                workspaceManagerService.cleanupWorkspace(disposableExecId);
                return handleCancellation(iteration, execution, currentUser, project, task);
            }

            // 13. Parse Retest Results (Phase 10.2)
            ParsedTestEvidence parsedEvidence = testResultParserCompositeService.parse(
                    ecosystem, execResult.getStdout(), execResult.getStderr()
            );

            // 14. Persist Rerun TestRun Entity
            Integer nextExecNum = previousRuns.size() + 1;
            TestRun rerunTestRun = new TestRun();
            rerunTestRun.setTask(task);
            rerunTestRun.setTaskExecution(execution);
            rerunTestRun.setExecutionNumber(nextExecNum);
            rerunTestRun.setEcosystem(ecosystem);
            rerunTestRun.setTestTarget(testTarget);
            rerunTestRun.setCommand(execResult.getCommandExecuted());
            rerunTestRun.setContainerId(execResult.getContainerId());
            rerunTestRun.setExitCode(execResult.getExitCode());
            rerunTestRun.setDurationMs(execResult.getDurationMs());
            rerunTestRun.setFailureType(execResult.getFailureType());
            rerunTestRun.setStdoutSummary(execResult.getStdout());
            rerunTestRun.setStderrSummary(execResult.getStderr());
            rerunTestRun.setStdoutTruncated(execResult.isStdoutTruncated());
            rerunTestRun.setStderrTruncated(execResult.isStderrTruncated());
            rerunTestRun.setTerminationReason(execResult.getTerminationReason());
            rerunTestRun.setStdoutBytesObserved(execResult.getStdoutBytesObserved());
            rerunTestRun.setStderrBytesObserved(execResult.getStderrBytesObserved());
            rerunTestRun.setTestsRun(parsedEvidence.getTestsRun());
            rerunTestRun.setFailuresCount(parsedEvidence.getFailuresCount());
            rerunTestRun.setErrorsCount(parsedEvidence.getErrorsCount());
            rerunTestRun.setSkippedCount(parsedEvidence.getSkippedCount());
            rerunTestRun.setPassedCount(parsedEvidence.getPassedCount());
            rerunTestRun.setParserStatus(parsedEvidence.getParserStatus());
            rerunTestRun.setFailureEvidence(toJson(parsedEvidence.getMetadata()));

            // 15. Perform Failure Reclassification via Phase 10.3 FailureClassifier
            FailureClassification classification = failureClassifierService.classify(ecosystem, execResult, approvedCodeChanges);
            rerunTestRun.setFailureCategory(classification.getFailureCategory());
            rerunTestRun.setRepairability(classification.getRepairability());
            rerunTestRun.setConfidence(classification.getConfidence());
            rerunTestRun.setClassificationReason(classification.getReason());
            rerunTestRun.setClassificationEvidence(toJson(classification.getEvidence()));
            rerunTestRun.setClassifiedAt(classification.getClassifiedAt());
            rerunTestRun.setClassifierVersion(classification.getClassifierVersion());

            // 16. Determine Deterministic D2 Outcome Policy
            String outcomePolicy = evaluateOutcomePolicy(baselineTestRun, rerunTestRun, execResult, classification);

            // Update TestRun & Iteration state based on outcome
            if ("REPAIR_SUCCESS".equals(outcomePolicy)) {
                rerunTestRun.setStatus("PASSED");
                iteration.setStatus(RepairIterationStatus.VERIFIED);
                execution.setStatus(ExecutionStatus.COMPLETED);
            } else if ("REPAIR_FAILED_TIMEOUT".equals(outcomePolicy)) {
                rerunTestRun.setStatus("TIMED_OUT");
                iteration.setStatus(RepairIterationStatus.TIMED_OUT);
            } else if ("REPAIR_FAILED_OOM".equals(outcomePolicy) || "REPAIR_FAILED_RESOURCE_LIMIT".equals(outcomePolicy) || "REPAIR_FAILED_NON_REPAIRABLE_FAILURE".equals(outcomePolicy)) {
                rerunTestRun.setStatus("FAILED");
                iteration.setStatus(RepairIterationStatus.NOT_REPAIRABLE);
            } else {
                rerunTestRun.setStatus("FAILED");
                iteration.setStatus(RepairIterationStatus.EXECUTION_FAILED);
            }

            rerunTestRun.setCompletedAt(Instant.now());
            TestRun savedTestRun = testRunRepository.save(rerunTestRun);

            iteration.setCompletedAt(Instant.now());
            RepairIteration savedIteration = repairIterationRepository.save(iteration);
            taskExecutionRepository.save(execution);

            auditLogService.logAction(currentUser, project, task, "REPAIR_EXECUTION_COMPLETED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("outcomePolicy", outcomePolicy, "exitCode", execResult.getExitCode() != null ? execResult.getExitCode() : -1)));

            // Clean up disposable workspace & container
            if (execResult.getContainerId() != null) {
                dockerSandboxExecutorService.terminateContainer(execResult.getContainerId());
            }
            workspaceManagerService.cleanupWorkspace(disposableExecId);

            // CRITICAL D2 INVARIANT: NO AUTO SECOND REPAIR IS PERMITTED
            // Stop immediately after single execution pass.

            return buildResponseDto(savedIteration, proposal, savedTestRun, outcomePolicy);

        } catch (Exception e) {
            logger.error("D2 repair execution failed for iteration {}: {}", iterationId, e.getMessage(), e);
            iteration.setStatus(RepairIterationStatus.EXECUTION_FAILED);
            iteration.setRejectionReason(e.getMessage());
            iteration.setCompletedAt(Instant.now());
            repairIterationRepository.save(iteration);

            return buildResponseDto(iteration, proposal, null, "REPAIR_FAILED_INFRASTRUCTURE");
        } finally {
            if (execResult != null && execResult.getContainerId() != null) {
                dockerSandboxExecutorService.terminateContainer(execResult.getContainerId());
            }
            activeExecutionLocks.remove(iterationId);
            workspaceManagerService.cleanupWorkspace(disposableExecId);
        }
    }

    /**
     * Deterministic D2 Outcome Evaluation Algorithm
     */
    private String evaluateOutcomePolicy(TestRun baseline, TestRun rerun, DockerExecutionResult result, FailureClassification classification) {
        if (result != null && result.getExitCode() != null && result.getExitCode() == 0 && rerun.getFailuresCount() == 0 && rerun.getErrorsCount() == 0) {
            return "REPAIR_SUCCESS";
        }

        if (result != null && result.getFailureType() == FailureType.TIMEOUT) {
            return "REPAIR_FAILED_TIMEOUT";
        }

        if (result != null && result.getFailureType() == FailureType.OOM) {
            return "REPAIR_FAILED_OOM";
        }

        if (result != null && result.getFailureType() == FailureType.RESOURCE_LIMIT) {
            return "REPAIR_FAILED_RESOURCE_LIMIT";
        }

        if (result != null && result.getFailureType() == FailureType.EXECUTION_INFRA_FAILURE) {
            return "REPAIR_FAILED_INFRASTRUCTURE";
        }

        if (classification.getRepairability() == Repairability.NOT_REPAIRABLE) {
            return "REPAIR_FAILED_NON_REPAIRABLE_FAILURE";
        }

        if (classification.getRepairability() == Repairability.AMBIGUOUS || classification.getFailureCategory() == FailureType.UNKNOWN) {
            return "REPAIR_FAILED_AMBIGUOUS";
        }

        // Compare baseline test failure with rerun test failure
        if (isSameFailure(baseline, rerun, classification)) {
            return "REPAIR_FAILED_SAME_FAILURE";
        }

        if (classification.getRepairability() == Repairability.REPAIRABLE) {
            return "REPAIR_FAILED_DIFFERENT_REPAIRABLE_FAILURE";
        }

        return "REPAIR_FAILED_SAME_FAILURE";
    }

    private boolean isSameFailure(TestRun baseline, TestRun rerun, FailureClassification classification) {
        if (baseline == null || rerun == null) return false;
        if (baseline.getFailureCategory() == rerun.getFailureCategory()) {
            if (baseline.getClassificationReason() != null && rerun.getClassificationReason() != null) {
                return baseline.getClassificationReason().equalsIgnoreCase(rerun.getClassificationReason());
            }
            return true;
        }
        return false;
    }

    private boolean hasUntrustedWorkspaceMutation(Path workspaceRepoDir, AIRepairProposal proposal, List<CodeChange> approvedCodeChanges) {
        try (Stream<Path> stream = Files.walk(workspaceRepoDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            Set<String> whitelistedPaths = extractWhitelistSet(approvedCodeChanges);

            for (Path file : files) {
                Path rel = workspaceRepoDir.relativize(file);
                String norm = rel.toString().replace("\\", "/").replaceAll("^/+", "");
                if (repairPolicyValidator.validatePathSecurity(workspaceRepoDir, workspaceRepoDir, norm).isValid()) {
                    if (!whitelistedPaths.contains(norm.toLowerCase())) {
                        // Unexpected non-whitelisted regular file modified/created by container
                        logger.warn("Untrusted container process created/modified file {}", norm);
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to walk workspace during untrusted mutation check: {}", e.getMessage());
        }
        return false;
    }

    private void applyProposalModificationsToWorkspace(AIRepairProposal proposal, Path workspaceRepoDir) throws IOException {
        if (proposal.getFileModifications() == null) return;
        AIRepairProposalDto dto;
        try {
            dto = objectMapper.readValue(proposal.getFileModifications(), AIRepairProposalDto.class);
        } catch (Exception e) {
            return;
        }

        if (dto != null && dto.getFileModifications() != null) {
            for (ProposedFileModificationDto mod : dto.getFileModifications()) {
                String relPath = mod.getFilePath() != null ? mod.getFilePath() : mod.getPath();
                if (relPath != null && !relPath.isBlank()) {
                    String normPath = relPath.replace("\\", "/").replaceAll("^/+", "");
                    Path targetPath = workspaceRepoDir.resolve(normPath).normalize();
                    String search = mod.getTargetContent() != null ? mod.getTargetContent() : mod.getSearch();
                    String replacement = mod.getReplacementContent() != null ? mod.getReplacementContent() : mod.getReplacement();
                    if (Files.exists(targetPath) && search != null && replacement != null) {
                        String content = Files.readString(targetPath, StandardCharsets.UTF_8);
                        if (content.contains(search)) {
                            Files.writeString(targetPath, content.replace(search, replacement), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
    }

    private boolean isCancelled(TaskExecution execution) {
        if (execution == null) return false;
        return Boolean.TRUE.equals(execution.getCancellationRequested()) || execution.getStatus() == ExecutionStatus.CANCELLED;
    }

    private RepairExecutionResponseDto handleCancellation(RepairIteration iteration, TaskExecution execution, User currentUser, Project project, Task task) {
        iteration.setStatus(RepairIterationStatus.CANCELLED);
        iteration.setRejectionReason("CANCELLATION_REQUESTED");
        iteration.setCompletedAt(Instant.now());
        repairIterationRepository.save(iteration);

        auditLogService.logAction(currentUser, project, task, "REPAIR_EXECUTION_CANCELLED", "REPAIR_ITERATION", iteration.getId(),
                toJson(Map.of("executionId", execution.getId().toString())));

        return buildResponseDto(iteration, null, null, "REPAIR_CANCELLED");
    }

    private List<CodeChange> getApprovedCodeChanges(TaskExecution execution, UUID taskId) {
        if (execution.getCodeChangeSnapshot() != null && !execution.getCodeChangeSnapshot().isBlank()) {
            try {
                return objectMapper.readValue(execution.getCodeChangeSnapshot(), new TypeReference<List<CodeChange>>() {});
            } catch (Exception e) {
                logger.warn("Failed to deserialize CodeChange snapshot: {}", e.getMessage());
            }
        }
        return codeChangeRepository.findByTaskId(taskId);
    }

    private Set<String> extractWhitelistSet(List<CodeChange> codeChanges) {
        Set<String> set = new HashSet<>();
        if (codeChanges != null) {
            for (CodeChange cc : codeChanges) {
                if (cc.getFilePath() != null && !cc.getFilePath().isBlank()) {
                    set.add(cc.getFilePath().replace("\\", "/").replaceAll("^/+", "").toLowerCase());
                }
            }
        }
        return set;
    }

    private String determineOutcomePolicyFromTerminalState(RepairIterationStatus status, TestRun latestRun) {
        if (status == RepairIterationStatus.VERIFIED) return "REPAIR_SUCCESS";
        if (status == RepairIterationStatus.TIMED_OUT) return "REPAIR_FAILED_TIMEOUT";
        if (status == RepairIterationStatus.CANCELLED) return "REPAIR_CANCELLED";
        if (status == RepairIterationStatus.NOT_REPAIRABLE) return "REPAIR_FAILED_NON_REPAIRABLE_FAILURE";
        if (status == RepairIterationStatus.VALIDATION_FAILED || status == RepairIterationStatus.REJECTED) return "REPAIR_SECURITY_REJECTED";
        return "REPAIR_FAILED_SAME_FAILURE";
    }

    private RepairExecutionResponseDto buildResponseDto(RepairIteration iteration, AIRepairProposal proposal, TestRun testRun, String outcomePolicy) {
        RepairExecutionResponseDto dto = new RepairExecutionResponseDto();
        dto.setRepairIterationId(iteration.getId());
        dto.setExecutionId(iteration.getTaskExecution().getId());
        dto.setIterationNumber(iteration.getIterationNumber());
        dto.setIterationStatus(iteration.getStatus().name());
        dto.setOutcomePolicy(outcomePolicy);
        dto.setSummaryReason(iteration.getRejectionReason());

        if (proposal != null) {
            dto.setProposalId(proposal.getId());
            dto.setParentSnapshotHash(proposal.getParentSnapshotHash());
            dto.setResultingTreeHash(proposal.getResultingTreeHash());
        }

        if (testRun != null) {
            dto.setTestRunId(testRun.getId());
            if (testRun.getFailureCategory() != null) {
                dto.setFailureCategory(testRun.getFailureCategory().name());
            }
            if (testRun.getRepairability() != null) {
                dto.setRepairability(testRun.getRepairability().name());
            }
            if (testRun.getConfidence() != null) {
                dto.setConfidence(testRun.getConfidence().name());
            }

            TestRunResponseDto trDto = new TestRunResponseDto();
            trDto.setId(testRun.getId());
            trDto.setTaskId(testRun.getTask().getId());
            trDto.setExecutionId(iteration.getTaskExecution().getId());
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
            trDto.setFailureCategory(testRun.getFailureCategory());
            trDto.setRepairability(testRun.getRepairability());
            trDto.setConfidence(testRun.getConfidence());
            trDto.setClassificationReason(testRun.getClassificationReason());
            trDto.setClassifierVersion(testRun.getClassifierVersion());
            trDto.setClassifiedAt(testRun.getClassifiedAt());
            trDto.setStartedAt(testRun.getStartedAt());
            trDto.setCompletedAt(testRun.getCompletedAt());
            dto.setTestRun(trDto);
        }

        dto.setCompletedAt(iteration.getCompletedAt() != null ? iteration.getCompletedAt() : Instant.now());
        return dto;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
