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
import com.repopilot.task.entity.Task;
import com.repopilot.task.entity.TaskExecution;
import com.repopilot.task.entity.ExecutionStatus;
import com.repopilot.task.repository.TaskExecutionRepository;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.testing.dto.AIDiagnosisResponseDto;
import com.repopilot.testing.dto.AIDiagnosisResultDto;
import com.repopilot.testing.dto.ValidationResult;
import com.repopilot.testing.entity.*;
import com.repopilot.testing.policy.RepairPolicyValidator;
import com.repopilot.testing.repository.AIDiagnosisRepository;
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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

@Service
public class AiDiagnosisServiceImpl implements AiDiagnosisService {

    private static final Logger logger = LoggerFactory.getLogger(AiDiagnosisServiceImpl.class);

    public static final int MAX_PROMPT_BYTES = 32 * 1024; // 32 KB
    public static final int MAX_TASK_DESC_BYTES = 2 * 1024; // 2 KB
    public static final int MAX_PLAN_BYTES = 4 * 1024; // 4 KB
    public static final int MAX_SOURCE_EXCERPT_BYTES = 8 * 1024; // 8 KB
    public static final int MAX_STACK_TRACE_BYTES = 8 * 1024; // 8 KB
    public static final int MAX_EVIDENCE_FILES = 5;
    public static final int MAX_ITERATIONS = 3;

    private static final List<String> PATCH_MARKERS = List.of(
            "<<<<<<<", ">>>>>>>", "diff --git", "```diff", "```python", "```java", "--- a/", "+++ b/", "search:", "replace:"
    );

    private final TaskExecutionRepository taskExecutionRepository;
    private final TaskRepository taskRepository;
    private final TestRunRepository testRunRepository;
    private final RepairIterationRepository repairIterationRepository;
    private final AIDiagnosisRepository aiDiagnosisRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final ProjectAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final FailureClassifierService failureClassifierService;
    private final RepairPolicyValidator repairPolicyValidator;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    @Value("${repopilot.storage.base-path:/tmp/repopilot-workspaces}")
    private String baseStoragePath = "/tmp/repopilot-workspaces";

    @Autowired
    public AiDiagnosisServiceImpl(TaskExecutionRepository taskExecutionRepository,
                                  TaskRepository taskRepository,
                                  TestRunRepository testRunRepository,
                                  RepairIterationRepository repairIterationRepository,
                                  AIDiagnosisRepository aiDiagnosisRepository,
                                  CodeChangeRepository codeChangeRepository,
                                  ProjectAuthorizationService authorizationService,
                                  AuditLogService auditLogService,
                                  FailureClassifierService failureClassifierService,
                                  RepairPolicyValidator repairPolicyValidator,
                                  AiServiceClient aiServiceClient,
                                  ObjectMapper objectMapper) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.taskRepository = taskRepository;
        this.testRunRepository = testRunRepository;
        this.repairIterationRepository = repairIterationRepository;
        this.aiDiagnosisRepository = aiDiagnosisRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.failureClassifierService = failureClassifierService;
        this.repairPolicyValidator = repairPolicyValidator;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AIDiagnosisResponseDto diagnoseIteration(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser) {
        // 1. Ownership & Authorization Verification
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

        // Idempotency: Return existing diagnosis if iteration already diagnosed or completed
        Optional<AIDiagnosis> existingDiagnosisOpt = aiDiagnosisRepository.findByRepairIterationId(iterationId);
        if (existingDiagnosisOpt.isPresent()) {
            return buildResponseDto(iteration, existingDiagnosisOpt.get(), null);
        }

        // 2. Pre-Eligibility Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        // 3. Server-Side Repair Eligibility Gate (11 Checks)
        List<TestRun> testRuns = testRunRepository.findByTaskExecutionId(executionId);
        TestRun latestTestRun = !testRuns.isEmpty() ? testRuns.get(testRuns.size() - 1) : null;

        String eligibilityRejection = verifyEligibility(execution, latestTestRun, iteration);

        auditLogService.logAction(currentUser, project, task, "DIAGNOSIS_ELIGIBILITY_CHECKED", "REPAIR_ITERATION", iterationId,
                toJson(Map.of("eligible", eligibilityRejection == null, "rejectionReason", eligibilityRejection != null ? eligibilityRejection : "")));

        if (eligibilityRejection != null) {
            iteration.setStatus(RepairIterationStatus.REJECTED);
            iteration.setRejectionReason(eligibilityRejection);
            iteration.setCompletedAt(Instant.now());
            repairIterationRepository.save(iteration);

            auditLogService.logAction(currentUser, project, task, "DIAGNOSIS_REJECTED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("reason", eligibilityRejection)));

            return buildResponseDto(iteration, null, eligibilityRejection);
        }

        // 3. Pre-Prompt Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        // Transition iteration status to DIAGNOSING
        iteration.setStatus(RepairIterationStatus.DIAGNOSING);
        iteration.setFailureCategory(latestTestRun.getFailureCategory());
        iteration.setRepairability(latestTestRun.getRepairability());
        repairIterationRepository.save(iteration);

        auditLogService.logAction(currentUser, project, task, "DIAGNOSIS_STARTED", "REPAIR_ITERATION", iterationId,
                toJson(Map.of("failureCategory", latestTestRun.getFailureCategory().name())));

        // 4. Extract Phase 9 Approved CodeChanges & Whitelist
        List<CodeChange> approvedCodeChanges = getApprovedCodeChanges(execution, taskId);
        List<String> approvedFilePaths = new ArrayList<>();
        Set<String> whitelistSet = new HashSet<>();
        for (CodeChange cc : approvedCodeChanges) {
            if (cc.getFilePath() != null && !cc.getFilePath().isBlank()) {
                String norm = cc.getFilePath().replace("\\", "/").replaceAll("^/+", "");
                approvedFilePaths.add(norm);
                whitelistSet.add(norm.toLowerCase());
            }
        }

        // 5. Construct Bounded & Sanitized AI Prompt Payload (Trusted Control vs Untrusted Evidence)
        Map<String, Object> promptPayload = buildPromptPayload(execution, task, latestTestRun, iteration, approvedCodeChanges, approvedFilePaths);

        // Pre-AI Call Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        // 6. Invoke AI Service / LLM Provider (with max 1 transient retry)
        Map<String, Object> rawAiResponseMap = null;
        String rawResponseString = null;
        String modelIdentifier = "repopilot-ai-diag-v1";

        try {
            rawAiResponseMap = aiServiceClient.diagnoseFailure(promptPayload);
            if (rawAiResponseMap != null) {
                rawResponseString = objectMapper.writeValueAsString(rawAiResponseMap);
            }
        } catch (Exception e) {
            logger.error("AI Service failure diagnosis call failed for iteration {}: {}", iterationId, e.getMessage());
            iteration.setStatus(RepairIterationStatus.AI_FAILURE);
            iteration.setRejectionReason("AI_PROVIDER_UNAVAILABLE: " + e.getMessage());
            iteration.setCompletedAt(Instant.now());
            repairIterationRepository.save(iteration);

            auditLogService.logAction(currentUser, project, task, "DIAGNOSIS_FAILED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("error", e.getMessage())));

            return buildResponseDto(iteration, null, iteration.getRejectionReason());
        }

        // Post-AI Call Cancellation Check
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        // 7. Output Validation (Schema, Size, Enums, Whitelist, Secret, Patch Markers)
        ValidationOutcome validationOutcome = validateAiOutput(rawResponseString, rawAiResponseMap, whitelistSet, repositoryId, approvedCodeChanges);

        if (!validationOutcome.isValid()) {
            logger.warn("AI diagnosis output validation failed for iteration {}: {}", iterationId, validationOutcome.getReason());
            iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
            iteration.setRejectionReason(validationOutcome.getErrorCode() + ": " + validationOutcome.getReason());
            iteration.setCompletedAt(Instant.now());
            repairIterationRepository.save(iteration);

            auditLogService.logAction(currentUser, project, task, "DIAGNOSIS_REJECTED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("reason", validationOutcome.getReason(), "errorCode", validationOutcome.getErrorCode())));

            return buildResponseDto(iteration, null, iteration.getRejectionReason());
        }

        // Pre-Persistence Cancellation & Stale Checks
        if (isCancelled(execution)) {
            return handleCancellation(iteration, execution, currentUser, project, task);
        }

        AIDiagnosisResultDto diagResult = validationOutcome.getResultDto();

        // 8. Persist AIDiagnosis Entity in PostgreSQL
        AIDiagnosis diagnosis = new AIDiagnosis();
        diagnosis.setRepairIteration(iteration);
        diagnosis.setRootCauseDescription(failureClassifierService.sanitize(diagResult.getRootCause()));
        diagnosis.setFailureMechanism(diagResult.getFailureMechanism());
        diagnosis.setProposedFixStrategy(failureClassifierService.sanitize(diagResult.getExplanation()));
        diagnosis.setConfidenceScore(diagResult.getConfidence());
        diagnosis.setRawResponse(failureClassifierService.sanitize(rawResponseString));
        diagnosis.setModelIdentifier(modelIdentifier);
        diagnosis.setCreatedAt(Instant.now());

        AIDiagnosis savedDiagnosis = aiDiagnosisRepository.save(diagnosis);

        // Update RepairIteration status to DIAGNOSED
        iteration.setStatus(RepairIterationStatus.DIAGNOSED);
        iteration.setCompletedAt(Instant.now());
        repairIterationRepository.save(iteration);

        auditLogService.logAction(currentUser, project, task, "DIAGNOSIS_COMPLETED", "REPAIR_ITERATION", iterationId,
                toJson(Map.of("diagnosisId", savedDiagnosis.getId().toString(), "confidence", diagResult.getConfidence(), "recommendation", diagResult.getRepairRecommendation())));

        return buildResponseDto(iteration, savedDiagnosis, null);
    }

    @Override
    @Transactional(readOnly = true)
    public AIDiagnosisResponseDto getDiagnosis(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser) {
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.VIEWER);

        TaskExecution execution = taskExecutionRepository.findByIdAndTaskIdAndRepositoryIdAndProjectId(executionId, taskId, repositoryId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXECUTION_NOT_FOUND", "Task execution not found"));

        RepairIteration iteration = repairIterationRepository.findById(iterationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ITERATION_NOT_FOUND", "Repair iteration not found"));

        AIDiagnosis diagnosis = aiDiagnosisRepository.findByRepairIterationId(iterationId).orElse(null);

        return buildResponseDto(iteration, diagnosis, iteration.getRejectionReason());
    }

    /**
     * Server-Side Eligibility Gate (11 Conditions)
     */
    private String verifyEligibility(TaskExecution execution, TestRun latestTestRun, RepairIteration iteration) {
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

        if (isCancelled(execution)) {
            return "EXECUTION_CANCELLED: Task execution has been cancelled";
        }

        if (iteration.getIterationNumber() > MAX_ITERATIONS) {
            return "MAX_ITERATIONS_EXCEEDED: Iteration number " + iteration.getIterationNumber() + " exceeds maximum limit of " + MAX_ITERATIONS;
        }

        if (execution.getStatus() == ExecutionStatus.ERROR || execution.getStatus() == ExecutionStatus.TIMED_OUT) {
            return "TERMINAL_EXECUTION_STATUS: Execution status is " + execution.getStatus();
        }

        return null;
    }

    private boolean isCancelled(TaskExecution execution) {
        if (execution == null) return false;
        return Boolean.TRUE.equals(execution.getCancellationRequested()) || execution.getStatus() == ExecutionStatus.CANCELLED;
    }

    private AIDiagnosisResponseDto handleCancellation(RepairIteration iteration, TaskExecution execution, User currentUser, Project project, Task task) {
        iteration.setStatus(RepairIterationStatus.CANCELLED);
        iteration.setRejectionReason("CANCELLATION_REQUESTED");
        iteration.setCompletedAt(Instant.now());
        repairIterationRepository.save(iteration);

        auditLogService.logAction(currentUser, project, task, "DIAGNOSIS_CANCELLED", "REPAIR_ITERATION", iteration.getId(),
                toJson(Map.of("executionId", execution.getId().toString())));

        return buildResponseDto(iteration, null, "CANCELLATION_REQUESTED");
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
     * Bounded & Sanitized AI Prompt Construction (Trusted Control vs Untrusted Evidence)
     */
    private Map<String, Object> buildPromptPayload(TaskExecution execution, Task task, TestRun testRun,
                                                   RepairIteration iteration, List<CodeChange> codeChanges,
                                                   List<String> approvedFilePaths) {
        Map<String, Object> trustedControl = new HashMap<>();
        trustedControl.put("taskId", task.getId().toString());
        trustedControl.put("executionId", execution.getId().toString());
        trustedControl.put("iterationNumber", iteration.getIterationNumber());
        trustedControl.put("failureCategory", testRun.getFailureCategory().name());
        trustedControl.put("classifierConfidence", testRun.getConfidence().name());
        trustedControl.put("classifierVersion", testRun.getClassifierVersion() != null ? testRun.getClassifierVersion() : "v1.0.0");
        trustedControl.put("approvedFilePaths", approvedFilePaths);
        trustedControl.put("limits", Map.of(
                "maxPromptBytes", MAX_PROMPT_BYTES,
                "maxTaskDescBytes", MAX_TASK_DESC_BYTES,
                "maxPlanBytes", MAX_PLAN_BYTES,
                "maxSourceExcerptBytes", MAX_SOURCE_EXCERPT_BYTES,
                "maxStackTraceBytes", MAX_STACK_TRACE_BYTES,
                "maxEvidenceFiles", MAX_EVIDENCE_FILES
        ));

        Map<String, Object> untrustedEvidence = new HashMap<>();

        String sanitizedTitle = failureClassifierService.sanitize(task.getTitle());
        String sanitizedDesc = failureClassifierService.sanitize(task.getDescription());
        if (sanitizedDesc != null && sanitizedDesc.getBytes().length > MAX_TASK_DESC_BYTES) {
            sanitizedDesc = new String(sanitizedDesc.getBytes(), 0, MAX_TASK_DESC_BYTES) + "\n[TRUNCATED BY SERVER POLICY]";
        }

        untrustedEvidence.put("taskTitle", sanitizedTitle);
        untrustedEvidence.put("taskDescription", sanitizedDesc != null ? sanitizedDesc : "");

        String rawLog = (testRun.getStderrSummary() != null && !testRun.getStderrSummary().isBlank())
                ? testRun.getStderrSummary()
                : (testRun.getStdoutSummary() != null ? testRun.getStdoutSummary() : "");
        String sanitizedLog = failureClassifierService.sanitize(rawLog);

        if (sanitizedLog != null && sanitizedLog.getBytes().length > MAX_STACK_TRACE_BYTES) {
            sanitizedLog = new String(sanitizedLog.getBytes(), 0, MAX_STACK_TRACE_BYTES) + "\n[TRUNCATED BY SERVER POLICY]";
        }
        untrustedEvidence.put("stackTraceSummary", sanitizedLog != null ? sanitizedLog : "");
        untrustedEvidence.put("classificationReason", failureClassifierService.sanitize(testRun.getClassificationReason()));

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
     * Strict Output Safety Validation
     */
    private ValidationOutcome validateAiOutput(String rawResponseString, Map<String, Object> rawAiResponseMap,
                                             Set<String> whitelistSet, UUID repositoryId, List<CodeChange> approvedCodeChanges) {

        if (rawResponseString == null || rawResponseString.isBlank()) {
            return ValidationOutcome.invalid("EMPTY_RESPONSE", "AI response string is null or empty");
        }

        if (rawResponseString.getBytes().length > RepairPolicyValidator.MAX_AI_RESPONSE_BYTES) {
            return ValidationOutcome.invalid("OVERSIZED_RESPONSE", "AI raw response exceeds 100KB limit");
        }

        // Secret Scanner Check
        String sanitized = failureClassifierService.sanitize(rawResponseString);
        if (!sanitized.equals(rawResponseString)) {
            return ValidationOutcome.invalid("SECRET_DETECTED", "Sensitive secret detected in raw AI response output");
        }

        // Check Patch/Code Generation Markers (No Code Generation Rule)
        for (String marker : PATCH_MARKERS) {
            if (rawResponseString.contains(marker)) {
                return ValidationOutcome.invalid("PATCH_CONTENT_REJECTED", "AI response contains code patch marker or executable diff syntax: " + marker);
            }
        }

        // Parse JSON DTO
        AIDiagnosisResultDto dto;
        try {
            if (rawAiResponseMap != null) {
                dto = objectMapper.convertValue(rawAiResponseMap, AIDiagnosisResultDto.class);
            } else {
                dto = objectMapper.readValue(rawResponseString, AIDiagnosisResultDto.class);
            }
        } catch (Exception e) {
            return ValidationOutcome.invalid("MALFORMED_JSON", "Failed to parse AI response into AIDiagnosisResultDto: " + e.getMessage());
        }

        if (dto == null) {
            return ValidationOutcome.invalid("MALFORMED_JSON", "Parsed AI diagnosis result DTO is null");
        }

        if (dto.getRootCause() == null || dto.getRootCause().isBlank()) {
            return ValidationOutcome.invalid("MISSING_REQUIRED_FIELD", "Diagnosis rootCause field is missing or empty");
        }

        if (dto.getRootCause().length() > 2000) {
            return ValidationOutcome.invalid("FIELD_LENGTH_EXCEEDED", "Diagnosis rootCause exceeds 2000 character limit");
        }

        if (dto.getFailureMechanism() == null || dto.getFailureMechanism().isBlank()) {
            return ValidationOutcome.invalid("MISSING_REQUIRED_FIELD", "Diagnosis failureMechanism field is missing or empty");
        }

        if (dto.getFailureMechanism().length() > 50) {
            return ValidationOutcome.invalid("FIELD_LENGTH_EXCEEDED", "Diagnosis failureMechanism exceeds 50 character limit");
        }

        if (dto.getExplanation() == null || dto.getExplanation().isBlank()) {
            return ValidationOutcome.invalid("MISSING_REQUIRED_FIELD", "Diagnosis explanation field is missing or empty");
        }

        if (dto.getExplanation().length() > 4000) {
            return ValidationOutcome.invalid("FIELD_LENGTH_EXCEEDED", "Diagnosis explanation exceeds 4000 character limit");
        }

        if (dto.getConfidence() == null || (!dto.getConfidence().equalsIgnoreCase("HIGH") &&
                                            !dto.getConfidence().equalsIgnoreCase("MEDIUM") &&
                                            !dto.getConfidence().equalsIgnoreCase("LOW"))) {
            return ValidationOutcome.invalid("INVALID_ENUM_VALUE", "Confidence enum must be HIGH, MEDIUM, or LOW");
        }

        if (dto.getRepairRecommendation() == null || (!dto.getRepairRecommendation().equalsIgnoreCase("REPAIR") &&
                                                     !dto.getRepairRecommendation().equalsIgnoreCase("DO_NOT_REPAIR") &&
                                                     !dto.getRepairRecommendation().equalsIgnoreCase("UNCERTAIN"))) {
            return ValidationOutcome.invalid("INVALID_ENUM_VALUE", "RepairRecommendation enum must be REPAIR, DO_NOT_REPAIR, or UNCERTAIN");
        }

        // Validate Affected Files (Whitelist, Path Security, Anti-Tampering, Forbidden Files)
        List<String> affectedFiles = dto.getAffectedFiles();
        if (affectedFiles != null && affectedFiles.size() > MAX_EVIDENCE_FILES) {
            return ValidationOutcome.invalid("EXCEEDS_FILE_LIMIT", "Affected files list exceeds limit of " + MAX_EVIDENCE_FILES + " files");
        }

        String baseDir = baseStoragePath != null ? baseStoragePath : "/tmp/repopilot-workspaces";
        String repoDirName = repositoryId != null ? repositoryId.toString() : "default-repo";
        Path masterRepoDir = Paths.get(baseDir, repoDirName);
        Path workspaceRepoDir = masterRepoDir;

        if (affectedFiles != null) {
            for (String file : affectedFiles) {
                if (file == null || file.isBlank()) continue;

                String norm = file.replace("\\", "/").replaceAll("^/+", "");

                // 1. Whitelist Check
                if (!whitelistSet.contains(norm.toLowerCase())) {
                    return ValidationOutcome.invalid("NOT_IN_WHITELIST", "Suggested affected file is not present in Phase 9 CodeChange whitelist: " + norm);
                }

                // 2. Mechanical Path Security Check
                ValidationResult pathVal = repairPolicyValidator.validatePathSecurity(workspaceRepoDir, masterRepoDir, norm);
                if (!pathVal.isValid()) {
                    return ValidationOutcome.invalid(pathVal.getErrorCode(), pathVal.getReason());
                }
            }
        }

        return ValidationOutcome.valid(dto);
    }

    private AIDiagnosisResponseDto buildResponseDto(RepairIteration iteration, AIDiagnosis diagnosis, String overrideRejection) {
        AIDiagnosisResponseDto dto = new AIDiagnosisResponseDto();
        dto.setRepairIterationId(iteration.getId());
        dto.setExecutionId(iteration.getTaskExecution().getId());
        dto.setIterationNumber(iteration.getIterationNumber());
        dto.setStatus(iteration.getStatus().name());
        dto.setFailureCategory(iteration.getFailureCategory() != null ? iteration.getFailureCategory().name() : null);
        dto.setRejectionReason(overrideRejection != null ? overrideRejection : iteration.getRejectionReason());
        dto.setCreatedAt(iteration.getCreatedAt());

        if (diagnosis != null) {
            dto.setDiagnosisId(diagnosis.getId());
            dto.setRootCauseDescription(diagnosis.getRootCauseDescription());
            dto.setFailureMechanism(diagnosis.getFailureMechanism());
            dto.setProposedFixStrategy(diagnosis.getProposedFixStrategy());
            dto.setConfidenceScore(diagnosis.getConfidenceScore());
            dto.setModelIdentifier(diagnosis.getModelIdentifier());
            dto.setCreatedAt(diagnosis.getCreatedAt());

            if (diagnosis.getRawResponse() != null) {
                try {
                    AIDiagnosisResultDto resultDto = objectMapper.readValue(diagnosis.getRawResponse(), AIDiagnosisResultDto.class);
                    dto.setAffectedFiles(resultDto.getAffectedFiles());
                    dto.setRepairRecommendation(resultDto.getRepairRecommendation());
                } catch (Exception e) {
                    logger.debug("Failed to extract affectedFiles from rawResponse: {}", e.getMessage());
                }
            }
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
        private final AIDiagnosisResultDto resultDto;

        private ValidationOutcome(boolean valid, String errorCode, String reason, AIDiagnosisResultDto resultDto) {
            this.valid = valid;
            this.errorCode = errorCode;
            this.reason = reason;
            this.resultDto = resultDto;
        }

        public static ValidationOutcome valid(AIDiagnosisResultDto dto) {
            return new ValidationOutcome(true, null, null, dto);
        }

        public static ValidationOutcome invalid(String errorCode, String reason) {
            return new ValidationOutcome(false, errorCode, reason, null);
        }

        public boolean isValid() { return valid; }
        public String getErrorCode() { return errorCode; }
        public String getReason() { return reason; }
        public AIDiagnosisResultDto getResultDto() { return resultDto; }
    }
}
