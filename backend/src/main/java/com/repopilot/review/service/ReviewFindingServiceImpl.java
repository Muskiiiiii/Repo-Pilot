package com.repopilot.review.service;

import com.repopilot.audit.service.AuditLogService;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.ReviewFindingResponseDto;
import com.repopilot.review.dto.UpdateFindingDispositionRequestDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ReviewFindingServiceImpl implements ReviewFindingService {

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final PreLlmSecretScanner preLlmSecretScanner;
    private final AuditLogService auditLogService;

    @Autowired
    public ReviewFindingServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            PreLlmSecretScanner preLlmSecretScanner,
            @Autowired(required = false) AuditLogService auditLogService) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewFindingResponseDto> getFindingsForReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            String severityFilter,
            User requester) {

        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required to query review findings");
        }

        // 1. Project Authorization
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        // 2. Resource Hierarchy Verification
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found"));
        if (!repo.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Repository does not belong to specified project");
        }

        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found"));
        if (!task.getRepository().getId().equals(repositoryId) || !task.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Task does not belong to specified project/repository");
        }

        CodeReview review = codeReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));

        if (!review.getProject().getId().equals(projectId) ||
            !review.getRepository().getId().equals(repositoryId) ||
            !review.getTask().getId().equals(taskId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "CodeReview does not belong to specified project/repository/task hierarchy");
        }

        // 3. Query Findings and Apply Optional Severity Filter
        List<ReviewFinding> findings = reviewFindingRepository.findByReview(review);

        if (severityFilter != null && !severityFilter.isBlank()) {
            final String filterUpper = severityFilter.trim().toUpperCase();
            try {
                FindingSeverity filterSeverity = FindingSeverity.valueOf(filterUpper);
                findings = findings.stream()
                        .filter(f -> f.getSeverity() == filterSeverity)
                        .collect(Collectors.toList());
            } catch (IllegalArgumentException ignored) {
                // Invalid severity filter -> return empty list or ignore
            }
        }

        // 4. Map to DTO with Secret Redaction and ID/Status
        return findings.stream().map(this::mapToDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewFindingResponseDto updateFindingDisposition(
            UUID projectId,
            UUID taskId,
            UUID reviewId,
            UUID findingId,
            UpdateFindingDispositionRequestDto request,
            User requester) {

        // 1. Authenticate Requester
        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        if (request == null || request.getStatus() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATE_TRANSITION", "Target finding status is required");
        }

        // 2. Initial Guard & Lookup
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        // 3. Acquire Pessimistic Write Lock on target ReviewFinding row bound to specified review (SELECT ... FOR UPDATE)
        ReviewFinding finding = reviewFindingRepository.findByIdAndReviewIdForUpdate(findingId, reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FINDING_NOT_FOUND", "ReviewFinding not found for specified review"));

        // 4. POST-LOCK Authoritative Hierarchy Validation (traverse locked entity graph)
        if (!finding.getReview().getId().equals(reviewId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FINDING_NOT_FOUND", "Finding does not belong to specified CodeReview");
        }
        if (!finding.getTask().getId().equals(taskId) || !finding.getReview().getTask().getId().equals(taskId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Finding hierarchy mismatch: task boundary");
        }
        Repository repository = finding.getRepository();
        if (repository == null || !finding.getReview().getRepository().getId().equals(repository.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Finding hierarchy mismatch: repository boundary");
        }
        if (!finding.getProject().getId().equals(projectId) || !finding.getReview().getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Finding hierarchy mismatch: project boundary");
        }

        // 5. Re-evaluate Authorization Role Post-Lock against locked project context
        ProjectRole userRole = projectAuthorizationService.getUserRoleInProject(finding.getProject(), requester);
        if (userRole == null || userRole == ProjectRole.VIEWER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions for finding disposition mutation");
        }

        FindingStatus currentStatus = finding.getStatus();
        FindingStatus targetStatus = request.getStatus();

        // 6. Check No-Op Condition (currentStatus == targetStatus)
        if (currentStatus == targetStatus) {
            // Return current DTO state with ZERO DB update and ZERO audit event emitted
            return mapToDto(finding);
        }

        // 7. Evaluate State Machine & RBAC Matrix
        validateRoleAndTransitionContract(currentStatus, targetStatus, userRole);

        // 8. Justification Sanitization & Length Pipeline
        String rawReason = request.getReason();
        String trimmedReason = rawReason != null ? rawReason.trim() : "";
        String sanitizedReason = preLlmSecretScanner.redactSecrets(trimmedReason);

        if (sanitizedReason.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG", "Justification reason must not exceed 1000 characters");
        }

        if (targetStatus == FindingStatus.RESOLVED && sanitizedReason.length() < 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_SHORT", "Justification reason of at least 5 characters is required for RESOLVED status");
        }
        if (targetStatus == FindingStatus.FALSE_POSITIVE && sanitizedReason.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_SHORT", "Justification reason of at least 10 characters is required for FALSE_POSITIVE status");
        }
        if (targetStatus == FindingStatus.SUPPRESSED && sanitizedReason.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_SHORT", "Justification reason of at least 10 characters is required for SUPPRESSED status");
        }

        // Reopening FALSE_POSITIVE or SUPPRESSED to OPEN requires min 5 chars reason
        if ((currentStatus == FindingStatus.FALSE_POSITIVE || currentStatus == FindingStatus.SUPPRESSED) &&
             targetStatus == FindingStatus.OPEN && sanitizedReason.length() < 5) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_SHORT", "Justification reason of at least 5 characters is required to reopen false-positive or suppressed findings");
        }

        // 9. Mutate Finding Status
        finding.setStatus(targetStatus);
        finding.setUpdatedAt(java.time.Instant.now());
        ReviewFinding savedFinding = reviewFindingRepository.save(finding);

        // 10. Persist AuditLog Entry Atomically
        if (auditLogService != null) {
            String metadataJson = String.format(
                    "{\"repositoryId\":\"%s\",\"reviewId\":\"%s\",\"previousStatus\":\"%s\",\"newStatus\":\"%s\",\"reason\":\"%s\"}",
                    repository.getId(),
                    reviewId,
                    currentStatus.name(),
                    targetStatus.name(),
                    escapeJson(sanitizedReason)
            );

            auditLogService.logAction(
                    requester,
                    finding.getProject(),
                    finding.getTask(),
                    "FINDING_DISPOSITION_UPDATED",
                    "REVIEW_FINDING",
                    finding.getId(),
                    metadataJson
            );
        }

        return mapToDto(savedFinding);
    }

    private void validateRoleAndTransitionContract(FindingStatus currentStatus, FindingStatus targetStatus, ProjectRole userRole) {
        boolean isAdminOrOwner = (userRole == ProjectRole.ADMIN || userRole == ProjectRole.OWNER);

        // 1. ADMIN/OWNER-only target statuses: FALSE_POSITIVE and SUPPRESSED
        if ((targetStatus == FindingStatus.FALSE_POSITIVE || targetStatus == FindingStatus.SUPPRESSED) && !isAdminOrOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "Admin or Owner role is required for FALSE_POSITIVE or SUPPRESSED finding dispositions");
        }

        // 2. Reopening from FALSE_POSITIVE or SUPPRESSED to any status requires ADMIN/OWNER
        if ((currentStatus == FindingStatus.FALSE_POSITIVE || currentStatus == FindingStatus.SUPPRESSED) && !isAdminOrOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "Admin or Owner role is required to reopen false-positive or suppressed findings");
        }
    }

    private ReviewFindingResponseDto mapToDto(ReviewFinding f) {
        ReviewFindingResponseDto dto = new ReviewFindingResponseDto(
                f.getFindingType(),
                f.getCategory(),
                f.getCweId(),
                f.getSeverity(),
                f.getConfidence(),
                preLlmSecretScanner.redactSecrets(f.getTitle() != null ? f.getTitle() : ""),
                preLlmSecretScanner.redactSecrets(f.getDescription() != null ? f.getDescription() : ""),
                f.getFilePath(),
                f.getStartLine(),
                f.getEndLine(),
                f.getSide(),
                "",
                f.getRemediationGuidance() != null ? preLlmSecretScanner.redactSecrets(f.getRemediationGuidance()) : null
        );
        dto.setId(f.getId());
        dto.setStatus(f.getStatus());
        return dto;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
