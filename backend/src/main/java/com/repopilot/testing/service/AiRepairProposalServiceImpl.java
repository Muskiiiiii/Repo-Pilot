package com.repopilot.testing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repopilot.ai.client.AiServiceClient;
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
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.dto.*;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.policy.RepairPolicyValidator;
import com.repopilot.testing.repository.AIDiagnosisRepository;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class AiRepairProposalServiceImpl implements AiRepairProposalService {

    private static final Logger logger = LoggerFactory.getLogger(AiRepairProposalServiceImpl.class);

    public static final int MAX_PROMPT_BYTES = 32 * 1024; // 32 KB
    public static final int MAX_TASK_DESC_BYTES = 2 * 1024; // 2 KB
    public static final int MAX_PLAN_BYTES = 4 * 1024; // 4 KB
    public static final int MAX_SOURCE_EXCERPT_BYTES = 8 * 1024; // 8 KB
    public static final int MAX_STACK_TRACE_BYTES = 8 * 1024; // 8 KB
    public static final int MAX_EVIDENCE_FILES = 5;
    public static final int MAX_ITERATIONS = 3;

    private static final List<String> FORBIDDEN_OPERATIONS = List.of(
            "CREATE", "DELETE", "RENAME", "MOVE", "COPY", "CHMOD", "EXECUTE", "SHELL", "DOWNLOAD", "NETWORK"
    );

    private static final List<String> PATCH_MARKERS = List.of(
            "<<<<<<<", ">>>>>>>", "diff --git", "```diff", "```python", "```java", "--- a/", "+++ b/"
    );

    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskRepository taskRepository;
    private final TestRunRepository testRunRepository;
    private final RepairIterationRepository repairIterationRepository;
    private final AIDiagnosisRepository aiDiagnosisRepository;
    private final AIRepairProposalRepository aiRepairProposalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final ProjectAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final FailureClassifierService failureClassifierService;
    private final RepairPolicyValidator repairPolicyValidator;
    private final WorkspaceManagerService workspaceManagerService;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    @Value("${repopilot.storage.base-path:/tmp/repopilot-workspaces}")
    private String baseStoragePath = "/tmp/repopilot-workspaces";

    @Autowired
    public AiRepairProposalServiceImpl(TaskExecutionRepository taskExecutionRepository,
                                       TaskRepository taskRepository,
                                       TestRunRepository testRunRepository,
                                       RepairIterationRepository repairIterationRepository,
                                       AIDiagnosisRepository aiDiagnosisRepository,
                                       AIRepairProposalRepository aiRepairProposalRepository,
                                       CodeChangeRepository codeChangeRepository,
                                       ProjectAuthorizationService authorizationService,
                                       AuditLogService auditLogService,
                                       FailureClassifierService failureClassifierService,
                                       RepairPolicyValidator repairPolicyValidator,
                                       WorkspaceManagerService workspaceManagerService,
                                       AiServiceClient aiServiceClient,
                                       ObjectMapper objectMapper) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.taskRepository = taskRepository;
        this.testRunRepository = testRunRepository;
        this.repairIterationRepository = repairIterationRepository;
        this.aiDiagnosisRepository = aiDiagnosisRepository;
        this.aiRepairProposalRepository = aiRepairProposalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.failureClassifierService = failureClassifierService;
        this.repairPolicyValidator = repairPolicyValidator;
        this.workspaceManagerService = workspaceManagerService;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AIRepairProposalResponseDto generateProposal(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser) {
        // 1. Authorization Verification
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.VIEWER);

        TaskExecution execution = taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found for given context"));

        Task task = execution.getTask();
        if (task == null || !task.getId().equals(taskId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TASK_MISMATCH", "Execution does not belong to the specified task");
        }

        RepairIteration iteration = repairIterationRepository.findById(iterationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ITERATION_NOT_FOUND", "Repair iteration not found"));

        if (!iteration.getTaskExecution().getId().equals(executionId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ITERATION_MISMATCH", "Repair iteration does not belong to execution " + executionId);
        }

        // Idempotency Check: Return existing proposal if already present
        Optional<AIRepairProposal> existingProposalOpt = aiRepairProposalRepository.findByRepairIterationId(iterationId);
        if (existingProposalOpt.isPresent()) {
            return buildResponseDto(iteration, existingProposalOpt.get());
        }

        // 2. Pre-Eligibility Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        // 3. Server-Side Eligibility Gate (17 Checks)
        List<TestRun> testRuns = testRunRepository.findByTaskExecutionId(executionId);
        TestRun latestTestRun = !testRuns.isEmpty() ? testRuns.get(testRuns.size() - 1) : null;

        AIDiagnosis diagnosis = aiDiagnosisRepository.findByRepairIterationId(iterationId).orElse(null);
        List<CodeChange> approvedCodeChanges = getApprovedCodeChanges(execution, taskId);

        String eligibilityRejection = verifyEligibility(execution, latestTestRun, iteration, diagnosis, approvedCodeChanges);

        auditLogService.logAction(currentUser, project, task, "REPAIR_PROPOSAL_ELIGIBILITY_CHECKED", "REPAIR_ITERATION", iterationId,
                toJson(Map.of("eligible", eligibilityRejection == null, "rejectionReason", eligibilityRejection != null ? eligibilityRejection : "")));

        if (eligibilityRejection != null) {
            iteration.setStatus(RepairIterationStatus.REJECTED);
            iteration.setRejectionReason(eligibilityRejection);
            iteration.setCompletedAt(Instant.now());
            repairIterationRepository.save(iteration);

            auditLogService.logAction(currentUser, project, task, "REPAIR_PROPOSAL_REJECTED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("reason", eligibilityRejection)));

            return buildResponseDto(iteration, null, "REJECTED", eligibilityRejection);
        }

        // 4. Extract Whitelist & Paths
        List<String> approvedFilePaths = new ArrayList<>();
        Set<String> whitelistSet = new HashSet<>();
        for (CodeChange cc : approvedCodeChanges) {
            if (cc.getFilePath() != null && !cc.getFilePath().isBlank()) {
                String norm = cc.getFilePath().replace("\\", "/").replaceAll("^/+", "");
                approvedFilePaths.add(norm);
                whitelistSet.add(norm.toLowerCase());
            }
        }

        // Transition iteration status to REPAIR_GENERATING
        iteration.setStatus(RepairIterationStatus.REPAIR_GENERATING);
        repairIterationRepository.save(iteration);

        auditLogService.logAction(currentUser, project, task, "REPAIR_PROPOSAL_STARTED", "REPAIR_ITERATION", iterationId,
                toJson(Map.of("diagnosisId", diagnosis.getId().toString())));

        // 5. Construct Bounded & Sanitized AI Prompt Payload
        Map<String, Object> promptPayload = buildPromptPayload(execution, task, latestTestRun, iteration, diagnosis, approvedCodeChanges, approvedFilePaths);

        // Pre-AI Call Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        // 6. Invoke AI Service / LLM Provider (with max 1 transient retry)
        Map<String, Object> rawAiResponseMap = null;
        String rawResponseString = null;
        String modelIdentifier = "repopilot-ai-proposal-v1";

        try {
            rawAiResponseMap = aiServiceClient.generateRepairProposal(promptPayload);
            if (rawAiResponseMap != null) {
                rawResponseString = objectMapper.writeValueAsString(rawAiResponseMap);
            }
        } catch (Exception e) {
            logger.error("AI Service repair proposal call failed for iteration {}: {}", iterationId, e.getMessage());
            iteration.setStatus(RepairIterationStatus.AI_FAILURE);
            iteration.setRejectionReason("AI_PROVIDER_UNAVAILABLE: " + e.getMessage());
            iteration.setCompletedAt(Instant.now());
            repairIterationRepository.save(iteration);

            auditLogService.logAction(currentUser, project, task, "REPAIR_PROPOSAL_FAILED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("error", e.getMessage())));

            return buildResponseDto(iteration, null, "REJECTED", iteration.getRejectionReason());
        }

        // Post-AI Call Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        // 7. Calculate Baseline Parent Snapshot Hash
        String baseDir = baseStoragePath != null ? baseStoragePath : "/tmp/repopilot-workspaces";
        String repoDirName = repositoryId != null ? repositoryId.toString() : "default-repo";
        Path masterRepoDir = Paths.get(baseDir, repoDirName);

        String parentSnapshotHash = calculateParentSnapshotHash(masterRepoDir);

        // 8. Output Validation & Deterministic Search/Replace In-Memory Validation
        ValidationOutcome validationOutcome = validateAndApplyInMem(rawResponseString, rawAiResponseMap, whitelistSet, repositoryId, masterRepoDir, approvedCodeChanges, parentSnapshotHash);

        if (!validationOutcome.isValid()) {
            logger.warn("AI repair proposal validation failed for iteration {}: {}", iterationId, validationOutcome.getReason());

            // Persist rejected proposal record in DB
            AIRepairProposal rejectedProposal = new AIRepairProposal();
            rejectedProposal.setRepairIteration(iteration);
            rejectedProposal.setDiagnosis(diagnosis);
            rejectedProposal.setFileModifications(failureClassifierService.sanitize(rawResponseString != null ? rawResponseString : "{}"));
            rejectedProposal.setValidationStatus(validationOutcome.getErrorCode());
            rejectedProposal.setValidationReason(validationOutcome.getReason());
            rejectedProposal.setParentSnapshotHash(parentSnapshotHash);
            rejectedProposal.setDiffHash(calculateSha256(rawResponseString != null ? rawResponseString : ""));
            rejectedProposal.setRawResponse(failureClassifierService.sanitize(rawResponseString != null ? rawResponseString : "{}"));
            rejectedProposal.setModelIdentifier(modelIdentifier);

            aiRepairProposalRepository.save(rejectedProposal);

            iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
            iteration.setRejectionReason(validationOutcome.getErrorCode() + ": " + validationOutcome.getReason());
            iteration.setCompletedAt(Instant.now());
            repairIterationRepository.save(iteration);

            auditLogService.logAction(currentUser, project, task, "REPAIR_PROPOSAL_REJECTED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("reason", validationOutcome.getReason(), "errorCode", validationOutcome.getErrorCode())));

            return buildResponseDto(iteration, rejectedProposal);
        }

        // Pre-Persistence Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        AIRepairProposalDto proposalDto = validationOutcome.getProposalDto();

        // 9. Persist Immutable Validated AIRepairProposal Entity
        AIRepairProposal proposal = new AIRepairProposal();
        proposal.setRepairIteration(iteration);
        proposal.setDiagnosis(diagnosis);
        proposal.setFileModifications(failureClassifierService.sanitize(toJson(proposalDto.getFileModifications())));
        proposal.setValidationStatus("VALIDATED");
        proposal.setValidationReason("Proposal passed all mechanical security, search/replace, secret, diff, and whitelist rules.");
        proposal.setParentSnapshotHash(parentSnapshotHash);
        proposal.setResultingTreeHash(validationOutcome.getResultingTreeHash());
        proposal.setDiffHash(validationOutcome.getDiffHash());
        proposal.setRawResponse(failureClassifierService.sanitize(rawResponseString));
        proposal.setModelIdentifier(modelIdentifier);
        proposal.setCreatedAt(Instant.now());

        AIRepairProposal savedProposal = aiRepairProposalRepository.save(proposal);

        // Update RepairIteration status to REPAIR_GENERATED
        iteration.setStatus(RepairIterationStatus.REPAIR_GENERATED);
        iteration.setCompletedAt(Instant.now());
        repairIterationRepository.save(iteration);

        auditLogService.logAction(currentUser, project, task, "REPAIR_PROPOSAL_COMPLETED", "REPAIR_ITERATION", iterationId,
                toJson(Map.of("proposalId", savedProposal.getId().toString(), "diffHash", validationOutcome.getDiffHash())));

        return buildResponseDto(iteration, savedProposal);
    }

    @Override
    @Transactional(readOnly = true)
    public AIRepairProposalResponseDto getProposal(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.VIEWER);

        TaskExecution execution = taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found"));

        RepairIteration iteration = repairIterationRepository.findById(iterationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ITERATION_NOT_FOUND", "Repair iteration not found"));

        AIRepairProposal proposal = aiRepairProposalRepository.findByRepairIterationId(iterationId).orElse(null);

        return buildResponseDto(iteration, proposal);
    }

    /**
     * Server-Side Repair Eligibility Gate (17 Checks)
     */
    private String verifyEligibility(TaskExecution execution, TestRun latestTestRun, RepairIteration iteration,
                                     AIDiagnosis diagnosis, List<CodeChange> approvedCodeChanges) {
        if (execution == null) return "EXECUTION_NULL";
        if (latestTestRun == null) return "NO_TEST_RUN_FOUND";
        if (latestTestRun.getFailureCategory() == null) return "NO_FAILURE_CLASSIFICATION";
        if (latestTestRun.getRepairability() == null) return "NO_REPAIRABILITY_CLASSIFICATION";

        if (latestTestRun.getRepairability() != Repairability.REPAIRABLE) {
            return "NOT_REPAIRABLE: Classification repairability is " + latestTestRun.getRepairability();
        }

        if (latestTestRun.getConfidence() != ConfidenceLevel.HIGH && latestTestRun.getConfidence() != ConfidenceLevel.MEDIUM) {
            return "LOW_CONFIDENCE: Classifier confidence level is " + latestTestRun.getConfidence();
        }

        FailureType cat = latestTestRun.getFailureCategory();
        if (cat != FailureType.CODE_FAILURE && cat != FailureType.TEST_FAILURE && cat != FailureType.COMPILATION_FAILURE) {
            return "NON_REPAIRABLE_FAILURE_TYPE: Failure category " + cat + " is strictly ineligible for repair";
        }

        if (diagnosis == null) {
            return "MISSING_DIAGNOSIS: Valid AIDiagnosis is required prior to repair proposal generation";
        }

        if (!diagnosis.getRepairIteration().getId().equals(iteration.getId())) {
            return "STALE_DIAGNOSIS: Diagnosis belongs to a different repair iteration";
        }

        if (isCancelled(execution)) {
            return "EXECUTION_CANCELLED: Task execution has been cancelled";
        }

        if (iteration.getIterationNumber() > MAX_ITERATIONS) {
            return "MAX_ITERATIONS_EXCEEDED: Iteration number " + iteration.getIterationNumber() + " exceeds maximum limit of " + MAX_ITERATIONS;
        }

        if (execution.getStatus() == ExecutionStatus.ERROR || execution.getStatus() == ExecutionStatus.TIMED_OUT) {
            return "TERMINAL_EXECUTION_STATUS: Execution status is " + execution.getStatus();
        }

        if (approvedCodeChanges == null || approvedCodeChanges.isEmpty()) {
            return "MISSING_CODE_CHANGES: Approved Phase 9 CodeChanges are required to establish whitelist boundary";
        }

        return null;
    }

    private boolean isCancelled(TaskExecution execution) {
        if (execution == null) return false;
        return Boolean.TRUE.equals(execution.getCancellationRequested()) || execution.getStatus() == ExecutionStatus.CANCELLED;
    }

    private AIRepairProposalResponseDto handleCancellation(RepairIteration iteration, TaskExecution execution, User currentUser, Project project, Task task) {
        iteration.setStatus(RepairIterationStatus.CANCELLED);
        iteration.setRejectionReason("CANCELLATION_REQUESTED");
        iteration.setCompletedAt(Instant.now());
        repairIterationRepository.save(iteration);

        auditLogService.logAction(currentUser, project, task, "REPAIR_PROPOSAL_CANCELLED", "REPAIR_ITERATION", iteration.getId(),
                toJson(Map.of("executionId", execution.getId().toString())));

        return buildResponseDto(iteration, null, "CANCELLED", "CANCELLATION_REQUESTED");
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

    /**
     * Bounded & Sanitized AI Prompt Construction
     */
    private Map<String, Object> buildPromptPayload(TaskExecution execution, Task task, TestRun testRun,
                                                   RepairIteration iteration, AIDiagnosis diagnosis,
                                                   List<CodeChange> codeChanges, List<String> approvedFilePaths) {
        Map<String, Object> trustedControl = new HashMap<>();
        trustedControl.put("taskId", task.getId().toString());
        trustedControl.put("executionId", execution.getId().toString());
        trustedControl.put("iterationNumber", iteration.getIterationNumber());
        trustedControl.put("diagnosisId", diagnosis.getId().toString());
        trustedControl.put("failureCategory", testRun.getFailureCategory().name());
        trustedControl.put("classifierConfidence", testRun.getConfidence().name());
        trustedControl.put("approvedFilePaths", approvedFilePaths);
        trustedControl.put("limits", Map.of(
                "maxPromptBytes", MAX_PROMPT_BYTES,
                "maxFiles", RepairPolicyValidator.MAX_FILES,
                "maxModifiedLines", RepairPolicyValidator.MAX_MODIFIED_LINES,
                "maxDiffBytes", RepairPolicyValidator.MAX_DIFF_BYTES
        ));

        Map<String, Object> untrustedEvidence = new HashMap<>();

        String sanitizedTitle = failureClassifierService.sanitize(task.getTitle());
        String sanitizedDesc = failureClassifierService.sanitize(task.getDescription());
        if (sanitizedDesc != null && sanitizedDesc.getBytes().length > MAX_TASK_DESC_BYTES) {
            sanitizedDesc = new String(sanitizedDesc.getBytes(), 0, MAX_TASK_DESC_BYTES) + "\n[TRUNCATED BY SERVER POLICY]";
        }

        untrustedEvidence.put("taskTitle", sanitizedTitle);
        untrustedEvidence.put("taskDescription", sanitizedDesc != null ? sanitizedDesc : "");
        untrustedEvidence.put("diagnosisRootCause", failureClassifierService.sanitize(diagnosis.getRootCauseDescription()));
        untrustedEvidence.put("diagnosisFixStrategy", failureClassifierService.sanitize(diagnosis.getProposedFixStrategy()));

        List<Map<String, String>> sourceExcerpts = new ArrayList<>();
        int count = 0;
        for (CodeChange cc : codeChanges) {
            if (count >= MAX_EVIDENCE_FILES) break;
            if (cc.getFilePath() != null && cc.getNewContent() != null) {
                String normPath = cc.getFilePath().replace("\\", "/").replaceAll("^/+", "");
                String sanitizedContent = failureClassifierService.sanitize(cc.getNewContent());
                if (sanitizedContent.getBytes().length > MAX_SOURCE_EXCERPT_BYTES) {
                    sanitizedContent = new String(sanitizedContent.getBytes(), 0, MAX_SOURCE_EXCERPT_BYTES) + "\n[TRUNCATED BY SERVER POLICY]";
                }
                sourceExcerpts.add(Map.of("path", normPath, "content", sanitizedContent));
                count++;
            }
        }
        untrustedEvidence.put("sourceExcerpts", sourceExcerpts);

        Map<String, Object> payload = new HashMap<>();
        payload.put("trustedControl", trustedControl);
        payload.put("untrustedEvidence", untrustedEvidence);

        return payload;
    }

    /**
     * Strict Output Safety & Search/Replace In-Memory Validation
     */
    private ValidationOutcome validateAndApplyInMem(String rawResponseString, Map<String, Object> rawAiResponseMap,
                                                   Set<String> whitelistSet, UUID repositoryId, Path masterRepoDir,
                                                   List<CodeChange> approvedCodeChanges, String parentSnapshotHash) {

        if (rawResponseString == null || rawResponseString.isBlank()) {
            return ValidationOutcome.invalid("EMPTY_RESPONSE", "AI proposal response string is null or empty");
        }

        if (rawResponseString.getBytes().length > RepairPolicyValidator.MAX_AI_RESPONSE_BYTES) {
            return ValidationOutcome.invalid("OVERSIZED_RESPONSE", "AI raw response exceeds 100KB limit");
        }

        // Secret Scanner Check
        String sanitized = failureClassifierService.sanitize(rawResponseString);
        if (!sanitized.equals(rawResponseString)) {
            return ValidationOutcome.invalid("SECRET_DETECTED", "Sensitive secret detected in raw AI proposal output");
        }

        // Check Patch/Code Generation Markers
        for (String marker : PATCH_MARKERS) {
            if (rawResponseString.contains(marker)) {
                return ValidationOutcome.invalid("PATCH_CONTENT_REJECTED", "AI response contains diff marker or git patch header: " + marker);
            }
        }

        // Parse JSON DTO
        AIRepairProposalDto dto;
        try {
            if (rawAiResponseMap != null) {
                dto = objectMapper.convertValue(rawAiResponseMap, AIRepairProposalDto.class);
            } else {
                dto = objectMapper.readValue(rawResponseString, AIRepairProposalDto.class);
            }
        } catch (Exception e) {
            return ValidationOutcome.invalid("MALFORMED_JSON", "Failed to parse AI repair proposal JSON: " + e.getMessage());
        }

        if (dto == null) {
            return ValidationOutcome.invalid("MALFORMED_JSON", "Parsed AI repair proposal DTO is null");
        }

        List<ProposedFileModificationDto> mods = dto.getFileModifications();
        if (mods == null || mods.isEmpty()) {
            return ValidationOutcome.invalid("MALFORMED_PROPOSAL", "Repair proposal contains no file modifications");
        }

        // Use RepairPolicyValidator for full 10-step mechanical check & bounds
        ValidationResult policyResult = repairPolicyValidator.validateRepairProposal(
                masterRepoDir, masterRepoDir, approvedCodeChanges, dto, parentSnapshotHash, parentSnapshotHash, rawResponseString
        );

        if (!policyResult.isValid()) {
            return ValidationOutcome.invalid(policyResult.getErrorCode(), policyResult.getReason());
        }

        int totalDiffBytes = 0;
        int totalModifiedLines = 0;
        StringBuilder diffSummaryBuilder = new StringBuilder();

        // Perform Search/Replace In-Memory Validation for each modification
        for (ProposedFileModificationDto mod : mods) {
            String op = mod.getOperation();
            if (op != null && FORBIDDEN_OPERATIONS.contains(op.toUpperCase())) {
                return ValidationOutcome.invalid("FORBIDDEN_OPERATION", "Operation '" + op + "' is forbidden. Only 'REPLACE' operation is supported.");
            }

            String pathStr = mod.getFilePath() != null ? mod.getFilePath() : mod.getPath();
            if (pathStr == null || pathStr.isBlank()) {
                return ValidationOutcome.invalid("INVALID_PATH", "Proposed modification file path is null or empty");
            }

            String normPath = pathStr.replace("\\", "/").replaceAll("^/+", "");

            String search = mod.getTargetContent() != null ? mod.getTargetContent() : mod.getSearch();
            String replacement = mod.getReplacementContent() != null ? mod.getReplacementContent() : mod.getReplacement();

            if (search == null || search.isEmpty()) {
                return ValidationOutcome.invalid("PATCH_CONTEXT_NOT_FOUND", "Proposed search string is null or empty for file " + normPath);
            }

            if (replacement == null) {
                replacement = "";
            }

            // Secret Scanner check on replacement text
            String sanitizedReplacement = failureClassifierService.sanitize(replacement);
            if (!sanitizedReplacement.equals(replacement)) {
                return ValidationOutcome.invalid("SECRET_DETECTED", "Sensitive secret material detected in proposed replacement text for " + normPath);
            }

            // Read original content from master repo baseline (or fall back to approvedCodeChanges snapshot)
            Path targetFile = masterRepoDir.resolve(normPath).normalize();
            String originalContent = "";
            if (Files.exists(targetFile) && Files.isRegularFile(targetFile)) {
                try {
                    originalContent = Files.readString(targetFile);
                } catch (Exception e) {
                    originalContent = "";
                }
            } else if (approvedCodeChanges != null) {
                for (CodeChange cc : approvedCodeChanges) {
                    if (cc.getFilePath() != null && cc.getFilePath().replace("\\", "/").replaceAll("^/+", "").equalsIgnoreCase(normPath)) {
                        originalContent = cc.getNewContent() != null ? cc.getNewContent() : (cc.getOldContent() != null ? cc.getOldContent() : "");
                        break;
                    }
                }
            }

            // Validate Search String Occurrences (Exact Single Match Required)
            int count = countOccurrences(originalContent, search);
            if (count == 0) {
                return ValidationOutcome.invalid("PATCH_CONTEXT_NOT_FOUND", "Proposed search string not found in master baseline file " + normPath);
            }
            if (count > 1) {
                return ValidationOutcome.invalid("PATCH_CONTEXT_AMBIGUOUS", "Proposed search string occurs " + count + " times in master baseline file " + normPath + ". Exact single match required.");
            }

            String resultingContent = originalContent.replace(search, replacement);

            // Secret Scanner check on resulting content
            String sanitizedResulting = failureClassifierService.sanitize(resultingContent);
            if (!sanitizedResulting.equals(resultingContent)) {
                return ValidationOutcome.invalid("SECRET_DETECTED", "Sensitive secret material detected in resulting file content for " + normPath);
            }

            totalDiffBytes += replacement.getBytes(StandardCharsets.UTF_8).length;
            totalModifiedLines += replacement.split("\r\n|\r|\n", -1).length;
            diffSummaryBuilder.append(normPath).append(":").append(search).append("->").append(replacement).append("\n");
        }

        String diffString = diffSummaryBuilder.toString();
        String diffHash = calculateSha256(diffString);
        String resultingTreeHash = calculateSha256(parentSnapshotHash + ":" + diffHash);

        return ValidationOutcome.valid(dto, diffHash, resultingTreeHash);
    }

    private int countOccurrences(String text, String search) {
        if (text == null || search == null || search.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    private String calculateParentSnapshotHash(Path masterRepoDir) {
        if (masterRepoDir != null && Files.exists(masterRepoDir)) {
            try {
                return workspaceManagerService.calculateTreeDigest(masterRepoDir);
            } catch (Exception e) {
                logger.warn("Failed to calculate tree digest: {}", e.getMessage());
            }
        }
        return calculateSha256("BASELINE:" + masterRepoDir);
    }

    private String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private AIRepairProposalResponseDto buildResponseDto(RepairIteration iteration, AIRepairProposal proposal) {
        if (proposal != null) {
            return buildResponseDto(iteration, proposal, proposal.getValidationStatus(), proposal.getValidationReason());
        }
        return buildResponseDto(iteration, null, iteration.getStatus().name(), iteration.getRejectionReason());
    }

    private AIRepairProposalResponseDto buildResponseDto(RepairIteration iteration, AIRepairProposal proposal, String overrideStatus, String overrideReason) {
        AIRepairProposalResponseDto dto = new AIRepairProposalResponseDto();
        dto.setRepairIterationId(iteration.getId());
        dto.setExecutionId(iteration.getTaskExecution().getId());
        dto.setIterationNumber(iteration.getIterationNumber());

        if (proposal != null) {
            dto.setProposalId(proposal.getId());
            if (proposal.getDiagnosis() != null) {
                dto.setDiagnosisId(proposal.getDiagnosis().getId());
            }
            dto.setValidationStatus(overrideStatus != null ? overrideStatus : proposal.getValidationStatus());
            dto.setValidationReason(overrideReason != null ? overrideReason : proposal.getValidationReason());
            dto.setParentSnapshotHash(proposal.getParentSnapshotHash());
            dto.setResultingTreeHash(proposal.getResultingTreeHash());
            dto.setDiffHash(proposal.getDiffHash());
            dto.setModelIdentifier(proposal.getModelIdentifier());
            dto.setCreatedAt(proposal.getCreatedAt());

            if (proposal.getFileModifications() != null) {
                try {
                    AIRepairProposalDto proposalDto = objectMapper.readValue(proposal.getFileModifications(), AIRepairProposalDto.class);
                    dto.setRationale(proposalDto.getRationale());
                    dto.setFileModifications(proposalDto.getFileModifications());
                } catch (Exception e) {
                    try {
                        List<ProposedFileModificationDto> list = objectMapper.readValue(proposal.getFileModifications(), new TypeReference<List<ProposedFileModificationDto>>() {});
                        dto.setFileModifications(list);
                    } catch (Exception ex) {
                        logger.debug("Failed to extract fileModifications from raw string: {}", ex.getMessage());
                    }
                }
            }
        } else {
            dto.setValidationStatus(overrideStatus != null ? overrideStatus : iteration.getStatus().name());
            dto.setValidationReason(overrideReason != null ? overrideReason : iteration.getRejectionReason());
            dto.setCreatedAt(iteration.getCreatedAt());
        }

        return dto;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static class ValidationOutcome {
        private final boolean valid;
        private final String errorCode;
        private final String reason;
        private final AIRepairProposalDto proposalDto;
        private final String diffHash;
        private final String resultingTreeHash;

        private ValidationOutcome(boolean valid, String errorCode, String reason, AIRepairProposalDto proposalDto, String diffHash, String resultingTreeHash) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.reason = reason;
            this.proposalDto = proposalDto;
            this.diffHash = diffHash;
            this.resultingTreeHash = resultingTreeHash;
        }

        public static ValidationOutcome valid(AIRepairProposalDto dto, String diffHash, String resultingTreeHash) {
            return new ValidationOutcome(true, null, null, dto, diffHash, resultingTreeHash);
        }

        public static ValidationOutcome invalid(String errorCode, String reason) {
            return new ValidationOutcome(false, errorCode, reason, null, null, null);
        }

        public boolean isValid() { return valid; }
        public String getErrorCode() { return errorCode; }
        public String getReason() { return reason; }
        public AIRepairProposalDto getProposalDto() { return proposalDto; }
        public String getDiffHash() { return diffHash; }
        public String getResultingTreeHash() { return resultingTreeHash; }
    }
}
