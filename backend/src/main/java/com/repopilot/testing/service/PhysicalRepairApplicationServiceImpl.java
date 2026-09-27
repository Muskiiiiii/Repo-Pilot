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
import com.repopilot.testing.dto.AIRepairProposalDto;
import com.repopilot.testing.dto.AIRepairProposalResponseDto;
import com.repopilot.testing.dto.ProposedFileModificationDto;
import com.repopilot.testing.dto.ValidationResult;
import com.repopilot.testing.entity.AIRepairProposal;
import com.repopilot.testing.entity.RepairIteration;
import com.repopilot.testing.entity.RepairIterationStatus;
import com.repopilot.testing.policy.RepairPolicyValidator;
import com.repopilot.testing.repository.AIRepairProposalRepository;
import com.repopilot.testing.repository.RepairIterationRepository;
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
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class PhysicalRepairApplicationServiceImpl implements PhysicalRepairApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(PhysicalRepairApplicationServiceImpl.class);
    private static final Map<UUID, String> activeApplicationLocks = new ConcurrentHashMap<>();

    private static final List<String> FORBIDDEN_OPERATIONS = List.of(
            "CREATE", "DELETE", "RENAME", "MOVE", "COPY", "CHMOD", "EXECUTE", "SHELL", "DOWNLOAD", "NETWORK", "SYMLINK", "DIRECTORY_MODIFICATION"
    );

    @Value("${repopilot.repository.storage-path:/app/data/repositories}")
    private String baseStoragePath;

    private final TaskExecutionRepository taskExecutionRepository;
    private final RepairIterationRepository repairIterationRepository;
    private final AIRepairProposalRepository aiRepairProposalRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final ProjectAuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final FailureClassifierService failureClassifierService;
    private final RepairPolicyValidator repairPolicyValidator;
    private final WorkspaceManagerService workspaceManagerService;
    private final ObjectMapper objectMapper;

    @Autowired
    public PhysicalRepairApplicationServiceImpl(TaskExecutionRepository taskExecutionRepository,
                                                RepairIterationRepository repairIterationRepository,
                                                AIRepairProposalRepository aiRepairProposalRepository,
                                                CodeChangeRepository codeChangeRepository,
                                                ProjectAuthorizationService authorizationService,
                                                AuditLogService auditLogService,
                                                FailureClassifierService failureClassifierService,
                                                RepairPolicyValidator repairPolicyValidator,
                                                WorkspaceManagerService workspaceManagerService,
                                                ObjectMapper objectMapper) {
        this.taskExecutionRepository = taskExecutionRepository;
        this.repairIterationRepository = repairIterationRepository;
        this.aiRepairProposalRepository = aiRepairProposalRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.failureClassifierService = failureClassifierService;
        this.repairPolicyValidator = repairPolicyValidator;
        this.workspaceManagerService = workspaceManagerService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AIRepairProposalResponseDto applyPhysicalRepair(UUID projectId, UUID repositoryId, UUID taskId, UUID executionId, UUID iterationId, User currentUser) {
        // 1. Authorization Verification
        Project project = authorizationService.getAuthorizedProject(projectId, currentUser);
        authorizationService.requireMinimumRole(project, currentUser, ProjectRole.MEMBER);

        // 2. Entity Resolution & Context Validation
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

        AIRepairProposal proposal = aiRepairProposalRepository.findByRepairIterationId(iterationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "AIRepairProposal not found for iteration " + iterationId));

        // 3. Idempotency Check: Return existing successful result if already physically applied
        if ("PHYSICALLY_APPLIED".equalsIgnoreCase(proposal.getValidationStatus()) || iteration.getStatus() == RepairIterationStatus.VALIDATED) {
            logger.info("Physical repair application already completed for iteration {}. Returning existing result (Idempotent).", iterationId);
            return buildResponseDto(iteration, proposal);
        }

        // 4. Concurrent Application Lock Guard
        String existingLock = activeApplicationLocks.putIfAbsent(iterationId, executionId.toString());
        if (existingLock != null) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_APPLICATION", "Physical repair application is already in progress for iteration " + iterationId);
        }

        UUID disposableExecId = UUID.randomUUID();

        try {
            // 5. Pre-Application Cancellation Check
            if (isCancelled(execution)) {
                return handleCancellation(iteration, execution, currentUser, project, task);
            }

            // 6. Baseline Master Repository Immutability Check (Before)
            Path masterRepoDir = Paths.get(baseStoragePath, repositoryId.toString());
            String masterDigestBefore = workspaceManagerService.calculateTreeDigest(masterRepoDir);

            // 7. Fresh Disposable Workspace Allocation & Parent State Reconstruction
            Path workspaceRepoDir = workspaceManagerService.materializeWorkspace(disposableExecId, execution.getRepository());

            List<CodeChange> approvedCodeChanges = getApprovedCodeChanges(execution, taskId);
            workspaceManagerService.applyCodeChangeSnapshot(disposableExecId, workspaceRepoDir, approvedCodeChanges);

            // Apply all prior VERIFIED repair iteration proposals (for lineage reconstruction)
            applyVerifiedPriorLineage(executionId, iteration.getIterationNumber(), workspaceRepoDir);

            // 8. Snapshot Tree Digest Binding Verification
            String activeWorkspaceTreeHash = workspaceManagerService.calculateTreeDigest(workspaceRepoDir);
            if (!activeWorkspaceTreeHash.equalsIgnoreCase(proposal.getParentSnapshotHash())) {
                logger.warn("Stale proposal detected for iteration {}: active workspace digest ({}) != parentSnapshotHash ({})",
                        iterationId, activeWorkspaceTreeHash, proposal.getParentSnapshotHash());

                proposal.setValidationStatus("STALE_REPAIR_PROPOSAL");
                proposal.setValidationReason("STALE_REPAIR_PROPOSAL: Active workspace tree digest (" + activeWorkspaceTreeHash +
                        ") does not match expected parent snapshot hash (" + proposal.getParentSnapshotHash() + ")");
                aiRepairProposalRepository.save(proposal);

                iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                iteration.setRejectionReason("STALE_REPAIR_PROPOSAL: Active workspace tree digest does not match expected parent snapshot hash");
                iteration.setCompletedAt(Instant.now());
                repairIterationRepository.save(iteration);

                workspaceManagerService.cleanupWorkspace(disposableExecId);

                auditLogService.logAction(currentUser, project, task, "REPAIR_PHYSICAL_APPLICATION_REJECTED", "REPAIR_ITERATION", iterationId,
                        toJson(Map.of("reason", "STALE_REPAIR_PROPOSAL", "activeTreeHash", activeWorkspaceTreeHash, "expectedHash", proposal.getParentSnapshotHash())));

                return buildResponseDto(iteration, proposal);
            }

            // 9. Parse Proposal DTO & Re-Validate Policy Constraints
            AIRepairProposalDto proposalDto;
            try {
                proposalDto = objectMapper.readValue(proposal.getFileModifications(), AIRepairProposalDto.class);
            } catch (Exception e) {
                proposalDto = parseProposalDtoFallback(proposal.getFileModifications());
            }

            if (proposalDto == null || proposalDto.getFileModifications() == null || proposalDto.getFileModifications().isEmpty()) {
                proposal.setValidationStatus("REJECTED");
                proposal.setValidationReason("MALFORMED_PROPOSAL: Proposal contains no valid file modifications");
                aiRepairProposalRepository.save(proposal);

                iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                iteration.setRejectionReason("MALFORMED_PROPOSAL: Proposal contains no valid file modifications");
                iteration.setCompletedAt(Instant.now());
                repairIterationRepository.save(iteration);

                workspaceManagerService.cleanupWorkspace(disposableExecId);
                return buildResponseDto(iteration, proposal);
            }

            ValidationResult policyResult = repairPolicyValidator.validateRepairProposal(
                    workspaceRepoDir, masterRepoDir, approvedCodeChanges, proposalDto, activeWorkspaceTreeHash, proposal.getParentSnapshotHash(), proposal.getRawResponse()
            );

            if (!policyResult.isValid()) {
                proposal.setValidationStatus(policyResult.getErrorCode());
                proposal.setValidationReason(policyResult.getReason());
                aiRepairProposalRepository.save(proposal);

                iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                iteration.setRejectionReason(policyResult.getErrorCode() + ": " + policyResult.getReason());
                iteration.setCompletedAt(Instant.now());
                repairIterationRepository.save(iteration);

                workspaceManagerService.cleanupWorkspace(disposableExecId);

                auditLogService.logAction(currentUser, project, task, "REPAIR_PHYSICAL_APPLICATION_REJECTED", "REPAIR_ITERATION", iterationId,
                        toJson(Map.of("reason", policyResult.getReason(), "errorCode", policyResult.getErrorCode())));

                return buildResponseDto(iteration, proposal);
            }

            // Mid-Application Cancellation Check
            if (isCancelled(execution)) {
                workspaceManagerService.cleanupWorkspace(disposableExecId);
                return handleCancellation(iteration, execution, currentUser, project, task);
            }

            // 10. TOCTOU Defense & Atomic Physical Replacement Loop
            Set<String> whitelistSet = extractWhitelistSet(approvedCodeChanges);

            for (ProposedFileModificationDto mod : proposalDto.getFileModifications()) {
                // Operation Restriction: ONLY REPLACE
                String op = mod.getOperation();
                if (op != null && FORBIDDEN_OPERATIONS.contains(op.toUpperCase())) {
                    proposal.setValidationStatus("FORBIDDEN_OPERATION");
                    proposal.setValidationReason("Operation '" + op + "' is strictly forbidden. Only 'REPLACE' operations are supported.");
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("FORBIDDEN_OPERATION: Operation '" + op + "' is forbidden.");
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                String relPath = mod.getFilePath() != null ? mod.getFilePath() : mod.getPath();
                if (relPath == null || relPath.isBlank()) {
                    proposal.setValidationStatus("INVALID_PATH");
                    proposal.setValidationReason("Proposed file modification path is null or empty");
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("INVALID_PATH: Path is null or empty");
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                String normPath = relPath.replace("\\", "/").replaceAll("^/+", "");

                // Mechanical Path Security Validation
                ValidationResult pathResult = repairPolicyValidator.validatePathSecurity(workspaceRepoDir, masterRepoDir, normPath);
                if (!pathResult.isValid()) {
                    proposal.setValidationStatus(pathResult.getErrorCode());
                    proposal.setValidationReason(pathResult.getReason());
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason(pathResult.getErrorCode() + ": " + pathResult.getReason());
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Approved Whitelist Restriction
                if (!whitelistSet.contains(normPath.toLowerCase())) {
                    proposal.setValidationStatus("NOT_IN_WHITELIST");
                    proposal.setValidationReason("Path is not present in approved Phase 9 CodeChange whitelist: " + normPath);
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("NOT_IN_WHITELIST: Path " + normPath + " is not whitelisted");
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Target File Existing Regular File & Symlink Checks
                Path targetPath = workspaceRepoDir.resolve(normPath).normalize();
                if (!Files.exists(targetPath) || !Files.isRegularFile(targetPath)) {
                    proposal.setValidationStatus("TARGET_NOT_FOUND");
                    proposal.setValidationReason("Target file does not exist or is not a regular file: " + normPath);
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("TARGET_NOT_FOUND: Target file " + normPath + " does not exist");
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                if (Files.isSymbolicLink(targetPath)) {
                    proposal.setValidationStatus("SYMLINK_DETECTED");
                    proposal.setValidationReason("Target path is a symbolic link: " + normPath);
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("SYMLINK_DETECTED: Target path is a symbolic link: " + normPath);
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                try {
                    Path realWorkspace = workspaceRepoDir.toRealPath();
                    Path realTarget = targetPath.toRealPath();
                    if (!realTarget.startsWith(realWorkspace)) {
                        proposal.setValidationStatus("CANONICAL_PATH_ESCAPE");
                        proposal.setValidationReason("Canonical target real path escapes workspace root: " + realTarget);
                        aiRepairProposalRepository.save(proposal);

                        iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                        iteration.setRejectionReason("CANONICAL_PATH_ESCAPE: " + normPath);
                        iteration.setCompletedAt(Instant.now());
                        repairIterationRepository.save(iteration);

                        workspaceManagerService.cleanupWorkspace(disposableExecId);
                        return buildResponseDto(iteration, proposal);
                    }
                } catch (IOException e) {
                    proposal.setValidationStatus("CANONICAL_PATH_FAILED");
                    proposal.setValidationReason("Failed to resolve canonical real path: " + e.getMessage());
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("CANONICAL_PATH_FAILED: " + e.getMessage());
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Target Parent Directory Verification
                Path parentDir = targetPath.getParent();
                if (parentDir == null || !Files.isDirectory(parentDir) || Files.isSymbolicLink(parentDir)) {
                    proposal.setValidationStatus("INVALID_PARENT");
                    proposal.setValidationReason("Parent directory for " + normPath + " is invalid or a symbolic link");
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("INVALID_PARENT: Parent dir for " + normPath + " is invalid");
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Target Content Match Verification
                String currentContent;
                try {
                    currentContent = Files.readString(targetPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    proposal.setValidationStatus("FILE_READ_FAILED");
                    proposal.setValidationReason("Failed to read current file content for " + normPath + ": " + e.getMessage());
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("FILE_READ_FAILED: " + e.getMessage());
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                String search = mod.getTargetContent() != null ? mod.getTargetContent() : mod.getSearch();
                String replacement = mod.getReplacementContent() != null ? mod.getReplacementContent() : mod.getReplacement();
                if (replacement == null) replacement = "";

                if (search == null || search.isEmpty()) {
                    proposal.setValidationStatus("PATCH_CONTEXT_NOT_FOUND");
                    proposal.setValidationReason("Target search content is null or empty for file " + normPath);
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("PATCH_CONTEXT_NOT_FOUND: Search content empty");
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                int matchCount = countOccurrences(currentContent, search);
                if (matchCount == 0) {
                    proposal.setValidationStatus("PATCH_CONTEXT_NOT_FOUND");
                    proposal.setValidationReason("Target search content not found in workspace file " + normPath);
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("PATCH_CONTEXT_NOT_FOUND: Search content not found in " + normPath);
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                if (matchCount > 1) {
                    proposal.setValidationStatus("PATCH_CONTEXT_AMBIGUOUS");
                    proposal.setValidationReason("Target search content occurs " + matchCount + " times in workspace file " + normPath + ". Exact single match required.");
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("PATCH_CONTEXT_AMBIGUOUS: " + matchCount + " matches in " + normPath);
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                String resultingContent = currentContent.replace(search, replacement);

                // Secret Scanner Checks
                String sanitizedReplacement = failureClassifierService.sanitize(replacement);
                if (!sanitizedReplacement.equals(replacement)) {
                    proposal.setValidationStatus("SECRET_DETECTED");
                    proposal.setValidationReason("Sensitive secret material detected in replacement content for " + normPath);
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("SECRET_DETECTED in replacement content for " + normPath);
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                String sanitizedResulting = failureClassifierService.sanitize(resultingContent);
                if (!sanitizedResulting.equals(resultingContent)) {
                    proposal.setValidationStatus("SECRET_DETECTED");
                    proposal.setValidationReason("Sensitive secret material detected in resulting content for " + normPath);
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("SECRET_DETECTED in resulting content for " + normPath);
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Exclusive Temporary Sidecar Creation & Atomic Replacement
                Path tempFile;
                try {
                    tempFile = Files.createTempFile(parentDir, ".repopilot_patch_tmp_", ".tmp");
                    Files.writeString(tempFile, resultingContent, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    proposal.setValidationStatus("TEMP_FILE_CREATE_FAILED");
                    proposal.setValidationReason("Failed to create atomic temporary patch file for " + normPath + ": " + e.getMessage());
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("TEMP_FILE_CREATE_FAILED: " + e.getMessage());
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Pre-Move Symlink & Containment Verification on Sidecar Temp File
                try {
                    if (Files.isSymbolicLink(tempFile) || !tempFile.toRealPath().startsWith(workspaceRepoDir.toRealPath())) {
                        Files.deleteIfExists(tempFile);
                        proposal.setValidationStatus("SYMLINK_DETECTED");
                        proposal.setValidationReason("Temporary patch file is a symbolic link or escapes workspace");
                        aiRepairProposalRepository.save(proposal);

                        iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                        iteration.setRejectionReason("SYMLINK_DETECTED on temporary sidecar file");
                        iteration.setCompletedAt(Instant.now());
                        repairIterationRepository.save(iteration);

                        workspaceManagerService.cleanupWorkspace(disposableExecId);
                        return buildResponseDto(iteration, proposal);
                    }
                } catch (IOException e) {
                    try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                    proposal.setValidationStatus("SYMLINK_CHECK_FAILED");
                    proposal.setValidationReason("Symlink check failed on temp file: " + e.getMessage());
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("SYMLINK_CHECK_FAILED: " + e.getMessage());
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Atomic Replacement
                try {
                    try {
                        Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        logger.warn("ATOMIC_MOVE unsupported on filesystem for {}, falling back to REPLACE_EXISTING", normPath);
                        Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
                    proposal.setValidationStatus("ATOMIC_MOVE_FAILED");
                    proposal.setValidationReason("Failed to atomically replace target file " + normPath + ": " + e.getMessage());
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("ATOMIC_MOVE_FAILED: " + e.getMessage());
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                // Post-Move Verification on Target File
                try {
                    if (!Files.isRegularFile(targetPath) || Files.isSymbolicLink(targetPath) || !targetPath.toRealPath().startsWith(workspaceRepoDir.toRealPath())) {
                        proposal.setValidationStatus("POST_WRITE_VERIFICATION_FAILED");
                        proposal.setValidationReason("Post-write verification failed for " + normPath + ": File is not regular, is symlink, or escaped root");
                        aiRepairProposalRepository.save(proposal);

                        iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                        iteration.setRejectionReason("POST_WRITE_VERIFICATION_FAILED: " + normPath);
                        iteration.setCompletedAt(Instant.now());
                        repairIterationRepository.save(iteration);

                        workspaceManagerService.cleanupWorkspace(disposableExecId);
                        return buildResponseDto(iteration, proposal);
                    }

                    String written = Files.readString(targetPath, StandardCharsets.UTF_8);
                    if (!written.equals(resultingContent)) {
                        proposal.setValidationStatus("POST_WRITE_VERIFICATION_FAILED");
                        proposal.setValidationReason("Written content hash mismatch post-move for " + normPath);
                        aiRepairProposalRepository.save(proposal);

                        iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                        iteration.setRejectionReason("POST_WRITE_VERIFICATION_FAILED: Content mismatch for " + normPath);
                        iteration.setCompletedAt(Instant.now());
                        repairIterationRepository.save(iteration);

                        workspaceManagerService.cleanupWorkspace(disposableExecId);
                        return buildResponseDto(iteration, proposal);
                    }
                } catch (IOException e) {
                    proposal.setValidationStatus("POST_WRITE_VERIFICATION_FAILED");
                    proposal.setValidationReason("Failed during post-move verification for " + normPath + ": " + e.getMessage());
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("POST_WRITE_VERIFICATION_FAILED: " + e.getMessage());
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }

                logger.info("Successfully applied physical REPLACE patch on '{}' in disposable workspace {}", normPath, disposableExecId);
            }

            // 11. Unexpected Sidecar File Inspection
            try (Stream<Path> stream = Files.walk(workspaceRepoDir)) {
                List<Path> sidecars = stream.filter(p -> p.getFileName().toString().startsWith(".repopilot_patch_tmp_")).toList();
                if (!sidecars.isEmpty()) {
                    for (Path sidecar : sidecars) {
                        try { Files.deleteIfExists(sidecar); } catch (Exception ignored) {}
                    }
                    logger.error("CRITICAL SECURITY INVARIANT VIOLATION: Unexpected temporary sidecar file remaining after patch application!");
                    proposal.setValidationStatus("UNEXPECTED_SIDECAR_DETECTED");
                    proposal.setValidationReason("SECURITY_INVARIANT_VIOLATION: Leftover temporary sidecar file detected");
                    aiRepairProposalRepository.save(proposal);

                    iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                    iteration.setRejectionReason("SECURITY_INVARIANT_VIOLATION: Unexpected sidecar detected");
                    iteration.setCompletedAt(Instant.now());
                    repairIterationRepository.save(iteration);

                    workspaceManagerService.cleanupWorkspace(disposableExecId);
                    return buildResponseDto(iteration, proposal);
                }
            } catch (IOException e) {
                logger.warn("Failed to walk workspace directory during sidecar inspection: {}", e.getMessage());
            }

            // 12. Resulting Workspace Tree Integrity Verification
            String actualResultingTreeHash = workspaceManagerService.calculateTreeDigest(workspaceRepoDir);
            if (proposal.getResultingTreeHash() != null && !actualResultingTreeHash.equalsIgnoreCase(proposal.getResultingTreeHash())) {
                logger.error("SECURITY INVARIANT VIOLATION: Resulting workspace tree hash ({}) does not match expected proposal tree hash ({})",
                        actualResultingTreeHash, proposal.getResultingTreeHash());

                proposal.setValidationStatus("RESULTING_TREE_HASH_MISMATCH");
                proposal.setValidationReason("SECURITY_INVARIANT_VIOLATION: Actual resulting tree hash (" + actualResultingTreeHash +
                        ") does not match expected proposal tree hash (" + proposal.getResultingTreeHash() + ")");
                aiRepairProposalRepository.save(proposal);

                iteration.setStatus(RepairIterationStatus.VALIDATION_FAILED);
                iteration.setRejectionReason("SECURITY_INVARIANT_VIOLATION: Resulting tree hash mismatch");
                iteration.setCompletedAt(Instant.now());
                repairIterationRepository.save(iteration);

                workspaceManagerService.cleanupWorkspace(disposableExecId);

                auditLogService.logAction(currentUser, project, task, "REPAIR_PHYSICAL_APPLICATION_REJECTED", "REPAIR_ITERATION", iterationId,
                        toJson(Map.of("reason", "RESULTING_TREE_HASH_MISMATCH", "actualTreeHash", actualResultingTreeHash, "expectedTreeHash", proposal.getResultingTreeHash())));

                return buildResponseDto(iteration, proposal);
            }

            // 13. Master Repository Immutability Check (After)
            String masterDigestAfter = workspaceManagerService.calculateTreeDigest(masterRepoDir);
            if (!masterDigestBefore.isBlank() && !masterDigestBefore.equals(masterDigestAfter)) {
                logger.error("CRITICAL SECURITY INVARIANT VIOLATION: Master repository storage mutated during physical repair application for iteration {}!", iterationId);
                workspaceManagerService.cleanupWorkspace(disposableExecId);
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SECURITY_INVARIANT_VIOLATION", "Master repository baseline content was mutated during physical patch application");
            }

            // Pre-Persistence Cancellation Check
            if (isCancelled(execution)) {
                workspaceManagerService.cleanupWorkspace(disposableExecId);
                return handleCancellation(iteration, execution, currentUser, project, task);
            }

            // 14. Persist Successful Application Metadata & Update Lineage States
            proposal.setValidationStatus("PHYSICALLY_APPLIED");
            proposal.setValidationReason("Physical repair patch applied cleanly and verified against resulting workspace tree digest (" + actualResultingTreeHash + ")");
            proposal.setResultingTreeHash(actualResultingTreeHash);
            AIRepairProposal savedProposal = aiRepairProposalRepository.save(proposal);

            iteration.setStatus(RepairIterationStatus.VALIDATED);
            iteration.setCompletedAt(Instant.now());
            RepairIteration savedIteration = repairIterationRepository.save(iteration);

            auditLogService.logAction(currentUser, project, task, "REPAIR_PHYSICALLY_APPLIED", "REPAIR_ITERATION", iterationId,
                    toJson(Map.of("proposalId", savedProposal.getId().toString(), "resultingTreeHash", actualResultingTreeHash)));

            // Clean up disposable workspace
            workspaceManagerService.cleanupWorkspace(disposableExecId);

            return buildResponseDto(savedIteration, savedProposal);

        } finally {
            // Clean up exclusive lock & disposable workspace
            activeApplicationLocks.remove(iterationId);
            workspaceManagerService.cleanupWorkspace(disposableExecId);
        }
    }

    private void applyVerifiedPriorLineage(UUID executionId, Integer currentIterationNum, Path workspaceRepoDir) {
        if (currentIterationNum == null || currentIterationNum <= 1) return;

        List<RepairIteration> priorIterations = repairIterationRepository.findByTaskExecutionIdOrderByIterationNumberAsc(executionId);
        for (RepairIteration prior : priorIterations) {
            if (prior.getIterationNumber() < currentIterationNum && prior.getStatus() == RepairIterationStatus.VERIFIED) {
                Optional<AIRepairProposal> priorProposalOpt = aiRepairProposalRepository.findByRepairIterationId(prior.getId());
                if (priorProposalOpt.isPresent()) {
                    AIRepairProposal priorProposal = priorProposalOpt.get();
                    try {
                        AIRepairProposalDto dto = objectMapper.readValue(priorProposal.getFileModifications(), AIRepairProposalDto.class);
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
                    } catch (Exception e) {
                        logger.warn("Failed to apply prior verified iteration {} lineage: {}", prior.getIterationNumber(), e.getMessage());
                    }
                }
            }
        }
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

        auditLogService.logAction(currentUser, project, task, "REPAIR_PHYSICAL_APPLICATION_CANCELLED", "REPAIR_ITERATION", iteration.getId(),
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

    private AIRepairProposalDto parseProposalDtoFallback(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, AIRepairProposalDto.class);
        } catch (Exception e) {
            try {
                List<ProposedFileModificationDto> list = objectMapper.readValue(json, new TypeReference<List<ProposedFileModificationDto>>() {});
                AIRepairProposalDto dto = new AIRepairProposalDto();
                dto.setFileModifications(list);
                return dto;
            } catch (Exception ex) {
                return null;
            }
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
}
