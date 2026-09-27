package com.repopilot.testing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
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
import com.repopilot.testing.parser.ParsedTestEvidence;
import com.repopilot.testing.parser.TestResultParserCompositeService;
import com.repopilot.testing.policy.*;
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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MultiIterationRepairOrchestrationServiceImpl implements MultiIterationRepairOrchestrationService {

    private static final Logger logger = LoggerFactory.getLogger(MultiIterationRepairOrchestrationServiceImpl.class);

    private final TaskExecutionRepository taskExecutionRepository;
    private final RepairIterationRepository repairIterationRepository;
    private final AIRepairProposalRepository aiRepairProposalRepository;
    private final TestRunRepository testRunRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final ProjectAuthorizationService authorizationService;
    private final WorkspaceManagerService workspaceManagerService;
    private final AiDiagnosisService aiDiagnosisService;
    private final AiRepairProposalService aiRepairProposalService;
    private final PhysicalRepairApplicationService physicalRepairApplicationService;
    private final SingleIterationRepairExecutionService singleIterationRepairExecutionService;
    private final FailureClassifierService failureClassifierService;
    private final TestResultParserCompositeService testResultParserCompositeService;
    private final FailureFingerprintService failureFingerprintService;
    private final RepairBudgetManager repairBudgetManager;
    private final DurableOrchestratorOwnershipService ownershipService;
    private final D3DecisionEngine decisionEngine;

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    private final Map<UUID, Boolean> activeOrchestrationLocks = new ConcurrentHashMap<>();

    @Autowired
    public MultiIterationRepairOrchestrationServiceImpl(TaskExecutionRepository taskExecutionRepository,
                                                        RepairIterationRepository repairIterationRepository,
                                                        AIRepairProposalRepository aiRepairProposalRepository,
                                                        TestRunRepository testRunRepository,
                                                        CodeChangeRepository codeChangeRepository,
                                                        ProjectAuthorizationService authorizationService,
                                                        WorkspaceManagerService workspaceManagerService,
                                                        AiDiagnosisService aiDiagnosisService,
                                                        AiRepairProposalService aiRepairProposalService,
                                                        PhysicalRepairApplicationService physicalRepairApplicationService,
                                                        SingleIterationRepairExecutionService singleIterationRepairExecutionService,
                                                        FailureClassifierService failureClassifierService,
                                                        TestResultParserCompositeService testResultParserCompositeService,
                                                        FailureFingerprintService failureFingerprintService,
                                                        RepairBudgetManager repairBudgetManager,
                                                        DurableOrchestratorOwnershipService ownershipService) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.repairIterationRepository = repairIterationRepository;
        this.aiRepairProposalRepository = aiRepairProposalRepository;
        this.testRunRepository = testRunRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.authorizationService = authorizationService;
        this.workspaceManagerService = workspaceManagerService;
        this.aiDiagnosisService = aiDiagnosisService;
        this.aiRepairProposalService = aiRepairProposalService;
        this.physicalRepairApplicationService = physicalRepairApplicationService;
        this.singleIterationRepairExecutionService = singleIterationRepairExecutionService;
        this.failureClassifierService = failureClassifierService;
        this.testResultParserCompositeService = testResultParserCompositeService;
        this.failureFingerprintService = failureFingerprintService;
        this.repairBudgetManager = repairBudgetManager;
        this.ownershipService = ownershipService != null ? ownershipService : new DurableOrchestratorOwnershipServiceImpl(null);
        this.decisionEngine = new D3DecisionEngine();
    }

    public MultiIterationRepairOrchestrationServiceImpl(TaskExecutionRepository taskExecutionRepository,
                                                        RepairIterationRepository repairIterationRepository,
                                                        AIRepairProposalRepository aiRepairProposalRepository,
                                                        TestRunRepository testRunRepository,
                                                        CodeChangeRepository codeChangeRepository,
                                                        ProjectAuthorizationService authorizationService,
                                                        WorkspaceManagerService workspaceManagerService,
                                                        AiDiagnosisService aiDiagnosisService,
                                                        AiRepairProposalService aiRepairProposalService,
                                                        PhysicalRepairApplicationService physicalRepairApplicationService,
                                                        SingleIterationRepairExecutionService singleIterationRepairExecutionService,
                                                        FailureClassifierService failureClassifierService,
                                                        TestResultParserCompositeService testResultParserCompositeService,
                                                        FailureFingerprintService failureFingerprintService,
                                                        RepairBudgetManager repairBudgetManager) {
        this(taskExecutionRepository, repairIterationRepository, aiRepairProposalRepository, testRunRepository, codeChangeRepository,
             authorizationService, workspaceManagerService, aiDiagnosisService, aiRepairProposalService, physicalRepairApplicationService,
             singleIterationRepairExecutionService, failureClassifierService, testResultParserCompositeService, failureFingerprintService,
             repairBudgetManager, new DurableOrchestratorOwnershipServiceImpl(null));
    }

    @Override
    public MultiIterationOrchestrationResponseDto orchestrateSelfCorrection(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, User currentUser) {
        if (executionId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXECUTION_ID", "Execution ID must not be null");
        }

        // 1. Process-Local Optimization Fast-Path Guard
        if (activeOrchestrationLocks.putIfAbsent(executionId, Boolean.TRUE) != null) {
            logger.warn("REPAIR_DUPLICATE: Active orchestration already running locally for execution {}", executionId);
            throw new ApiException(HttpStatus.CONFLICT, "REPAIR_DUPLICATE", "An active repair orchestration is already running for execution " + executionId);
        }

        // 2. Authoritative Durable Cross-Instance Ownership Acquisition with Server Token
        String ownerToken = ownershipService.acquireOwnership(executionId, Duration.ofSeconds(60));
        if (ownerToken == null) {
            activeOrchestrationLocks.remove(executionId);
            logger.warn("REPAIR_DUPLICATE: Durable ownership lease already held for execution {}", executionId);
            throw new ApiException(HttpStatus.CONFLICT, "REPAIR_DUPLICATE", "An active repair orchestration is already running for execution " + executionId);
        }

        // Start Periodic Heartbeat Lease Renewal
        ownershipService.startHeartbeat(executionId, ownerToken, Duration.ofSeconds(15), Duration.ofSeconds(60));

        // Atomic PostgreSQL leadership claim & fencing epoch increment
        int claimed = taskExecutionRepository.claimLeadershipAndIncrementEpoch(executionId, ownerToken);
        if (claimed == 0) {
            TaskExecution currentExec = taskExecutionRepository.findById(executionId).orElse(null);
            if (currentExec != null && (currentExec.getStatus() == ExecutionStatus.COMPLETED ||
                                       currentExec.getStatus() == ExecutionStatus.CANCELLED ||
                                       currentExec.getStatus() == ExecutionStatus.FAILED ||
                                       currentExec.getStatus() == ExecutionStatus.TIMED_OUT)) {
                ownershipService.releaseOwnership(executionId, ownerToken);
                activeOrchestrationLocks.remove(executionId);
                throw new ApiException(HttpStatus.CONFLICT, "EXECUTION_TERMINAL", "Task execution is already in terminal status: " + currentExec.getStatus());
            }
            if (currentExec != null && currentExec.getFencingEpoch() == null) {
                currentExec.setFencingEpoch(1L);
            }
        }

        long startMs = System.currentTimeMillis();
        List<UUID> executedIterationIds = new ArrayList<>();
        int iterationsRun = 0;
        D3DecisionResult finalResult = null;

        try {
            TaskExecution execution = taskExecutionRepository.findById(executionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "TaskExecution not found"));
            Long fencingEpoch = execution.getFencingEpoch() != null ? execution.getFencingEpoch() : 1L;

            if (execution.getStatus() == ExecutionStatus.PREPARING || execution.getStatus() == ExecutionStatus.CREATED) {
                taskExecutionRepository.updateStatusWithFencingAndVersion(executionId, ExecutionStatus.RUNNING, null, execution.getVersion(), fencingEpoch);
                execution = taskExecutionRepository.findById(executionId).orElse(execution);
            }

            Project project = execution.getProject();
            Repository repository = execution.getRepository();
            Task task = execution.getTask();

            if (project != null && currentUser != null) {
                Project authProject = authorizationService.getAuthorizedProject(projectId, currentUser);
                authorizationService.requireMinimumRole(authProject, currentUser, ProjectRole.MEMBER);
            }

            // Verify Master Repository Content Digest Before Loop
            String storageBase = baseStoragePath != null ? baseStoragePath : "/app/data/repositories";
            Path masterRepoDir = Paths.get(storageBase, repository.getId().toString());
            String masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

            // Fetch Baseline TestRun
            List<TestRun> previousRuns = testRunRepository.findByTaskExecutionId(executionId);
            if (previousRuns.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BASELINE_TEST_RUN_MISSING", "Canonical baseline TestRun missing for execution " + executionId);
            }
            TestRun baselineTestRun = previousRuns.get(0);
            List<CodeChange> approvedCodeChanges = codeChangeRepository.findByTaskId(task.getId());
            Ecosystem ecosystem = execution.getEcosystem() != null ? execution.getEcosystem() : Ecosystem.PYTHON;

            // Extract Baseline Failure Fingerprint Set
            ParsedTestEvidence baselineEvidence = testResultParserCompositeService.parse(ecosystem, baselineTestRun.getStdoutSummary(), baselineTestRun.getStderrSummary());
            FailureClassification baselineClassification = failureClassifierService.classify(ecosystem, toDockerResult(baselineTestRun), approvedCodeChanges);
            String baselineFingerprint = failureFingerprintService.generateFingerprint(ecosystem, baselineClassification, baselineEvidence, baselineTestRun.getStdoutSummary(), baselineTestRun.getStderrSummary());

            List<String> fingerprintHistory = new ArrayList<>();
            fingerprintHistory.add(baselineFingerprint);

            Set<String> baselineFailingSet = extractFailingFingerprints(baselineEvidence, baselineTestRun);

            // Check Baseline Clean Pass
            if (baselineTestRun.getExitCode() != null && baselineTestRun.getExitCode() == 0 && baselineEvidence.getFailuresCount() == 0 && baselineEvidence.getErrorsCount() == 0) {
                taskExecutionRepository.updateStatusWithFencingAndVersion(executionId, ExecutionStatus.COMPLETED, Instant.now(), execution.getVersion(), fencingEpoch);
                return new MultiIterationOrchestrationResponseDto(
                        executionId, 0, 1, D3DecisionType.TERMINATE, "REPAIR_SUCCESS",
                        "Baseline execution passed cleanly. No repair required.", System.currentTimeMillis() - startMs, List.of(), Instant.now()
                );
            }

            // Multi-Iteration Loop: Hard Upper Limit of 3 Repair Iterations
            for (int N = 1; N <= D3DecisionEngine.MAX_REPAIR_ITERATIONS; N++) {
                // Hard Iteration Guard (Iteration #4 Impossible)
                if (N > 3) {
                    finalResult = D3DecisionResult.terminate("MAX_ITERATIONS_REACHED", "Iteration ceiling 3 reached", baselineFailingSet, Set.of(), baselineFailingSet, N);
                    break;
                }

                // Stale Owner Guard
                verifyOwnershipOrThrow(executionId, ownerToken);

                // Check Cancellation
                if (Boolean.TRUE.equals(execution.getCancellationRequested())) {
                    finalResult = D3DecisionResult.terminate("REPAIR_CANCELLED", "Execution cancelled by user or system", Set.of(), Set.of(), baselineFailingSet, N);
                    break;
                }

                // Check Monotonic Repair Budget & Atomic Iteration Limit
                long elapsedSoFar = System.currentTimeMillis() - startMs;
                RepairBudgetSnapshot budgetSnapshot = repairBudgetManager.consumeRepairBudget(executionId, elapsedSoFar);
                if (!budgetSnapshot.isRepairAllowed() || budgetSnapshot.getRepairsConsumed() >= 3) {
                    String denialReason = budgetSnapshot.getDenialReason() != null && !budgetSnapshot.getDenialReason().isBlank()
                            ? budgetSnapshot.getDenialReason()
                            : "Maximum repair iteration limit reached (3)";
                    String outcomePolicy = budgetSnapshot.getRemainingExecutionMillis() <= 0 ? "CUMULATIVE_TIMEOUT" : "MAX_ITERATIONS_REACHED";
                    finalResult = D3DecisionResult.terminate(outcomePolicy, denialReason, Set.of(), Set.of(), baselineFailingSet, N);
                    break;
                }

                // DB Iteration Guard
                long dbIterCount = repairIterationRepository.countByTaskExecutionId(executionId);
                if (dbIterCount >= 3) {
                    finalResult = D3DecisionResult.terminate("MAX_ITERATIONS_REACHED", "Maximum repair iteration limit reached (3)", Set.of(), Set.of(), baselineFailingSet, N);
                    break;
                }

                // Step 1: Create or Reuse RepairIteration Record
                verifyOwnershipOrThrow(executionId, ownerToken);
                final int iterNum = N;
                final TaskExecution currentExecForIter = execution;
                RepairIteration iteration = repairIterationRepository.findByTaskExecutionIdAndIterationNumber(executionId, iterNum)
                        .orElseGet(() -> {
                            RepairIteration newIter = new RepairIteration();
                            newIter.setTaskExecution(currentExecForIter);
                            newIter.setIterationNumber(iterNum);
                            newIter.setStatus(RepairIterationStatus.CREATED);
                            return repairIterationRepository.save(newIter);
                        });

                executedIterationIds.add(iteration.getId());
                iterationsRun++;

                // Step 2: AI Diagnosis (Phase 10.4-B)
                verifyOwnershipOrThrow(executionId, ownerToken);
                AIDiagnosisResponseDto diagnosisDto = aiDiagnosisService.diagnoseIteration(projectId, repositoryId, taskId, executionId, iteration.getId(), currentUser);
                if ("AI_FAILURE".equals(diagnosisDto.getStatus()) || "FAILED".equals(diagnosisDto.getStatus())) {
                    finalResult = D3DecisionResult.terminate("REPAIR_FAILED_AMBIGUOUS", "AI Diagnosis generation failed: " + diagnosisDto.getRejectionReason(), Set.of(), Set.of(), baselineFailingSet, N);
                    break;
                }

                // Step 3: AI Repair Proposal (Phase 10.4-C)
                verifyOwnershipOrThrow(executionId, ownerToken);
                AIRepairProposalResponseDto proposalDto = aiRepairProposalService.generateProposal(projectId, repositoryId, taskId, executionId, iteration.getId(), currentUser);
                if ("REJECTED".equals(proposalDto.getValidationStatus()) || "VALIDATION_FAILED".equals(proposalDto.getValidationStatus())) {
                    finalResult = D3DecisionResult.terminate("REPAIR_SECURITY_REJECTED", "AI Proposal validation failed: " + proposalDto.getValidationReason(), Set.of(), Set.of(), baselineFailingSet, N);
                    break;
                }

                // Step 4 & 5 & 6 & 7: Fresh Disposable Workspace Materialization + D1 + D2
                verifyOwnershipOrThrow(executionId, ownerToken);
                UUID disposableExecId = UUID.randomUUID();
                RepairExecutionResponseDto d2Response = null;
                try {
                    // D2 Single Iteration Retest Execution
                    d2Response = singleIterationRepairExecutionService.executeRepairIteration(projectId, repositoryId, taskId, executionId, iteration.getId(), currentUser);
                } finally {
                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                }

                // Step 8: D3-B Fingerprinting & Sequence Analysis
                verifyOwnershipOrThrow(executionId, ownerToken);
                List<TestRun> rerunRuns = testRunRepository.findByTaskExecutionId(executionId);
                TestRun latestRerun = !rerunRuns.isEmpty() ? rerunRuns.get(rerunRuns.size() - 1) : null;
                String d2Stdout = d2Response != null && d2Response.getTestRun() != null && d2Response.getTestRun().getStdoutSummary() != null ? d2Response.getTestRun().getStdoutSummary() : "";
                String d2Stderr = d2Response != null && d2Response.getTestRun() != null && d2Response.getTestRun().getStderrSummary() != null ? d2Response.getTestRun().getStderrSummary() : "";
                ParsedTestEvidence rerunEvidence = testResultParserCompositeService.parse(ecosystem, d2Stdout, d2Stderr);
                FailureClassification rerunClassification = failureClassifierService.classify(ecosystem, toDockerResult(latestRerun), approvedCodeChanges);

                FailureFingerprintResult fpResult = failureFingerprintService.analyzeFingerprintSequence(ecosystem, rerunClassification, rerunEvidence, d2Stdout, d2Stderr, fingerprintHistory);
                fingerprintHistory.add(fpResult.getFingerprint());

                Set<String> currentFailingSet = extractFailingFingerprints(rerunEvidence, latestRerun);

                // Step 9: Build Immutable D3ExecutionFacts & Evaluate D3-A Decision Engine
                D3ExecutionFacts facts = D3ExecutionFacts.builder()
                        .iterationNumber(N)
                        .maxIterations(3)
                        .baselineFailingFingerprints(baselineFailingSet)
                        .currentFailingFingerprints(currentFailingSet)
                        .currentClassification(rerunClassification)
                        .currentParsedEvidence(rerunEvidence)
                        .terminationReason(latestRerun != null ? latestRerun.getTerminationReason() : TerminationReason.NORMAL_EXIT)
                        .exitCode(d2Response != null && d2Response.getTestRun() != null ? d2Response.getTestRun().getExitCode() : -1)
                        .isCancelled(Boolean.TRUE.equals(execution.getCancellationRequested()))
                        .isBudgetExceeded(!budgetSnapshot.isRepairAllowed())
                        .isSecurityRejected(d2Response != null && "REPAIR_SECURITY_REJECTED".equals(d2Response.getOutcomePolicy()))
                        .isMasterIntegrityValid(masterDigestBefore.equals(workspaceManagerService.calculateTreeDigest(masterRepoDir)))
                        .isWorkspaceIntegrityValid(true)
                        .isProposalBindingValid(true)
                        .isNoProgressDetected(fpResult.isRepeated() && N > 1)
                        .isCycleDetected(fpResult.isRepeated() || fpResult.isOscillating())
                        .rejectionReason(d2Response != null ? d2Response.getSummaryReason() : null)
                        .build();

                finalResult = decisionEngine.evaluate(facts);

                // Decision Action
                if (finalResult.getDecision() == D3DecisionType.TERMINATE) {
                    logger.info("D3 Decision TERMINATE reached at iteration {} with policy {}", N, finalResult.getOutcomePolicy());
                    break;
                }

                logger.info("D3 Decision CONTINUE reached at iteration {}, advancing to iteration {}", N, N + 1);
            }

            // Post-Orchestration Master Repo Tree Digest Check
            verifyOwnershipOrThrow(executionId, ownerToken);
            String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
            if (!masterDigestBefore.equalsIgnoreCase(masterDigestAfter)) {
                logger.error("SECURITY_INVARIANT_VIOLATION: Master repository storage mutated during multi-iteration repair orchestration!");
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SECURITY_INVARIANT_VIOLATION", "Master repository baseline content was mutated during repair orchestration");
            }

            if (finalResult == null) {
                finalResult = D3DecisionResult.terminate("REPAIR_FAILED_AMBIGUOUS", "Orchestration ended without explicit decision", baselineFailingSet, Set.of(), baselineFailingSet, iterationsRun);
            }

            ExecutionStatus finalStatus;
            if (finalResult != null && "REPAIR_SUCCESS".equalsIgnoreCase(finalResult.getOutcomePolicy())) {
                finalStatus = ExecutionStatus.COMPLETED;
            } else if (finalResult != null && "REPAIR_CANCELLED".equalsIgnoreCase(finalResult.getOutcomePolicy())) {
                finalStatus = ExecutionStatus.CANCELLED;
            } else {
                finalStatus = ExecutionStatus.FAILED;
            }
            taskExecutionRepository.updateStatusWithFencingAndVersion(executionId, finalStatus, Instant.now(), execution.getVersion(), fencingEpoch);

            return new MultiIterationOrchestrationResponseDto(
                    executionId, iterationsRun, iterationsRun + 1, finalResult.getDecision(),
                    finalResult.getOutcomePolicy(), finalResult.getDecisionReason(), System.currentTimeMillis() - startMs,
                    executedIterationIds, Instant.now()
            );

        } finally {
            try {
                ownershipService.releaseOwnership(executionId, ownerToken);
            } catch (Exception e) {
                logger.warn("Error releasing ownership lock for execution {}: {}", executionId, e.getMessage());
            }
            activeOrchestrationLocks.remove(executionId);
        }
    }

    private void verifyOwnershipOrThrow(UUID executionId, String ownerToken) {
        if (!ownershipService.verifyOwnership(executionId, ownerToken)) {
            logger.error("STALE_OWNER: Worker with owner token {} lost ownership for execution {}", ownerToken, executionId);
            throw new ApiException(HttpStatus.CONFLICT, "STALE_OWNER", "Orchestration ownership was lost or superseded for execution " + executionId);
        }
    }

    private DockerExecutionResult toDockerResult(TestRun testRun) {
        if (testRun == null) {
            return new DockerExecutionResult(1, "", "", 0L, "c1", FailureType.UNKNOWN, false, false, "cmd");
        }
        return new DockerExecutionResult(
                testRun.getExitCode() != null ? testRun.getExitCode() : -1,
                testRun.getStdoutSummary(),
                testRun.getStderrSummary(),
                testRun.getDurationMs() != null ? testRun.getDurationMs() : 0L,
                testRun.getContainerId(),
                testRun.getFailureCategory() != null ? testRun.getFailureCategory() : FailureType.UNKNOWN,
                Boolean.TRUE.equals(testRun.getStdoutTruncated()),
                Boolean.TRUE.equals(testRun.getStderrTruncated()),
                testRun.getCommand()
        );
    }

    private Set<String> extractFailingFingerprints(ParsedTestEvidence evidence, TestRun testRun) {
        Set<String> set = new HashSet<>();
        if (evidence != null && evidence.getMetadata() != null && evidence.getMetadata().containsKey("failingTests")) {
            Object obj = evidence.getMetadata().get("failingTests");
            if (obj instanceof List<?> l) {
                for (Object item : l) {
                    if (item != null) set.add(item.toString().trim());
                }
            }
        }
        if (set.isEmpty() && testRun != null && testRun.getClassificationReason() != null) {
            set.add(testRun.getClassificationReason());
        }
        return set;
    }
}
