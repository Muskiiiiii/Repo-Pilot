package com.repopilot.review.service;

import com.repopilot.audit.service.AuditLogService;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.AdminReviewOverrideRequest;
import com.repopilot.review.dto.AdminReviewOverrideResponseDto;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.ReviewStatus;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class AdminReviewOverrideServiceImpl implements AdminReviewOverrideService {

    private static final Logger logger = LoggerFactory.getLogger(AdminReviewOverrideServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final ReviewContextService reviewContextService;
    private final PreLlmSecretScanner preLlmSecretScanner;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public AdminReviewOverrideServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeReviewRepository codeReviewRepository,
            CodeChangeRepository codeChangeRepository,
            ReviewContextService reviewContextService,
            PreLlmSecretScanner preLlmSecretScanner,
            @Autowired(required = false) AuditLogService auditLogService,
            TransactionTemplate transactionTemplate) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.reviewContextService = reviewContextService;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
        this.auditLogService = auditLogService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public AdminReviewOverrideResponseDto overrideReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            AdminReviewOverrideRequest request,
            User adminUser) {

        // 1. Authenticate Requester
        if (adminUser == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User must be authenticated to perform admin override");
        }

        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OVERRIDE_REASON", "Admin override reason cannot be null or blank");
        }

        String rawReason = request.getReason().trim();
        if (rawReason.length() < 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_SHORT", "Admin override reason must be at least 10 characters");
        }
        if (rawReason.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG", "Admin override reason must not exceed 1000 characters");
        }

        // 2. Initial Guard & Authorization Check (OWNER treated as ADMIN-equivalent)
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, adminUser);
        projectAuthorizationService.requireMinimumRole(project, adminUser, ProjectRole.ADMIN);

        // 3. Initial Resource Hierarchy Validation
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

        CodeReview reviewLookup = codeReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));

        if (!reviewLookup.getProject().getId().equals(projectId) ||
            !reviewLookup.getRepository().getId().equals(repositoryId) ||
            !reviewLookup.getTask().getId().equals(taskId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "CodeReview does not belong to specified project/repository/task hierarchy");
        }

        final String sanitizedReason = preLlmSecretScanner.redactSecrets(rawReason);
        final Instant now = Instant.now();

        // 4. Dual-Entity Database Row Locking & Atomic Mutation Sequence (Option A)
        try {
            return transactionTemplate.execute(status -> {
                // Step A: Lock CodeChange row first (Parent Entity) to prevent concurrent context mutation
                if (reviewLookup.getCodeChange() != null && codeChangeRepository != null) {
                    codeChangeRepository.findByIdForUpdate(reviewLookup.getCodeChange().getId());
                }

                // Step B: Lock CodeReview row second (Child Entity) via SELECT FOR UPDATE
                CodeReview review = codeReviewRepository.findByIdWithLock(reviewId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));

                // Re-evaluate Hierarchy Post-Lock against locked entity graph
                if (!review.getProject().getId().equals(projectId) ||
                    !review.getRepository().getId().equals(repositoryId) ||
                    !review.getTask().getId().equals(taskId)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "CodeReview hierarchy mismatch under lock");
                }

                // Step C: TOCTOU Context Re-Verification
                if (review.getCodeChange() != null && reviewContextService != null) {
                    ReviewContext currentCtx = reviewContextService.buildReviewContext(
                            projectId, repositoryId, taskId, review.getCodeChange().getId(), review.getReviewMode(), adminUser
                    );
                    if (currentCtx == null || currentCtx.isStale() || !currentCtx.reviewContextHash().equalsIgnoreCase(review.getReviewContextHash())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "STALE_CONTEXT", "CodeChange context became stale during override processing");
                    }
                }

                // Step D: Verify Review Status == COMPLETED
                if (review.getStatus() != ReviewStatus.COMPLETED) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS",
                            "Admin override can only be performed on COMPLETED reviews. Current status is " + review.getStatus());
                }

                // Step E: Double Override Check
                if (review.getAdminOverrideReason() != null || review.getAdminOverrideByUser() != null) {
                    throw new ApiException(HttpStatus.CONFLICT, "OVERRIDE_ALREADY_EXISTS", "Admin override has already been recorded for this review");
                }

                // Step F: Execute Atomic SQL Mutation
                int rows = codeReviewRepository.recordAdminOverrideAtomic(
                        reviewId, sanitizedReason, adminUser, now, ReviewStatus.COMPLETED
                );
                if (rows == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "OVERRIDE_ALREADY_EXISTS", "Admin override has already been recorded for this review");
                }

                // Step G: Persist Audit Event Atomically in the SAME Transaction
                if (auditLogService != null) {
                    String metadataJson = String.format(
                            "{\"projectId\":\"%s\",\"repositoryId\":\"%s\",\"taskId\":\"%s\",\"reviewId\":\"%s\",\"reason\":\"%s\",\"adminUserId\":\"%s\"}",
                            projectId, repositoryId, taskId, reviewId, escapeJson(sanitizedReason), adminUser.getId()
                    );
                    auditLogService.logAction(
                            adminUser, project, task, "ADMIN_REVIEW_OVERRIDE_RECORDED", "CODE_REVIEW", reviewId, metadataJson
                    );
                }

                CodeReview updatedReview = codeReviewRepository.findById(reviewId).orElseThrow();
                logger.info("Admin override successfully recorded for review {} by admin user {}", reviewId, adminUser.getId());

                return new AdminReviewOverrideResponseDto(
                        updatedReview.getId(),
                        updatedReview.getProject().getId(),
                        updatedReview.getRepository().getId(),
                        updatedReview.getTask().getId(),
                        updatedReview.getCodeChange() != null ? updatedReview.getCodeChange().getId() : null,
                        updatedReview.getStatus(),
                        updatedReview.getAdminOverrideReason(),
                        adminUser.getId(),
                        adminUser.getName(),
                        updatedReview.getAdminOverrideAt()
                );
            });
        } catch (DataIntegrityViolationException e) {
            logger.warn("Database trigger rejected admin override update for review {}: {}", reviewId, e.getMessage());
            throw new ApiException(HttpStatus.CONFLICT, "OVERRIDE_REJECTED", "Database immutability policy rejected admin override: record is immutable or already overridden.");
        }
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
