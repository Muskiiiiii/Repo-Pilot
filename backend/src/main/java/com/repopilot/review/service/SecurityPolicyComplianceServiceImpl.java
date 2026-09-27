package com.repopilot.review.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.PolicyViolationDto;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.SecurityPolicyComplianceDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 13 Slice 11 Implementation of SecurityPolicyComplianceService.
 * Executes read-only policy compliance evaluations within PostgreSQL REPEATABLE_READ snapshot isolation.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SecurityPolicyComplianceServiceImpl implements SecurityPolicyComplianceService {

    private static final Logger logger = LoggerFactory.getLogger(SecurityPolicyComplianceServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final ReviewContextService reviewContextService;
    private final PreLlmSecretScanner preLlmSecretScanner;

    public SecurityPolicyComplianceServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            ReviewContextService reviewContextService,
            PreLlmSecretScanner preLlmSecretScanner) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.reviewContextService = reviewContextService;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
    }

    @Override
    public SecurityPolicyComplianceDto evaluatePolicyCompliance(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            User requester) {

        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        // Step 1: Authorization - Minimum VIEWER role required
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        // Step 2: Load Task & Validate Project/Repository Hierarchy
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found: " + taskId));

        if (!task.getProject().getId().equals(projectId) || !task.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "HIERARCHY_MISMATCH", "Task hierarchy mismatch for project or repository");
        }

        // Step 3: Load CodeReview & Validate Hierarchy
        CodeReview review = codeReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found: " + reviewId));

        if (!review.getProject().getId().equals(projectId) ||
            !review.getRepository().getId().equals(repositoryId) ||
            !review.getTask().getId().equals(taskId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "HIERARCHY_MISMATCH", "CodeReview hierarchy mismatch for specified path parameters");
        }

        // Step 4: Verify Terminal Review Status
        if (review.getStatus() != ReviewStatus.COMPLETED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS",
                    "Security policy compliance evaluation requires a COMPLETED review status (current status: " + review.getStatus() + ")");
        }

        // Step 5: Re-verify Canonical Review Context Hash (TOCTOU Defense)
        if (review.getCodeChange() != null && reviewContextService != null) {
            ReviewContext currentCtx = reviewContextService.buildReviewContext(
                    projectId, repositoryId, taskId, review.getCodeChange().getId(), review.getReviewMode(), requester
            );
            if (currentCtx == null || currentCtx.isStale() || !currentCtx.reviewContextHash().equalsIgnoreCase(review.getReviewContextHash())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "STALE_CONTEXT",
                        "Review context hash does not match current CodeChange state");
            }
        }

        // Step 6: Validate Slice 10 Administrative Override State
        String overrideReason = review.getAdminOverrideReason();
        User overrideUser = review.getAdminOverrideByUser();
        Instant overrideAt = review.getAdminOverrideAt();

        boolean hasReason = overrideReason != null && !overrideReason.trim().isEmpty() && overrideReason.trim().length() >= 10;
        boolean hasUser = overrideUser != null;
        boolean hasAt = overrideAt != null;

        boolean isExempted;
        if (hasReason && hasUser && hasAt) {
            isExempted = true;
        } else if (!hasReason && !hasUser && !hasAt) {
            isExempted = false;
        } else {
            // Corrupt or partially populated override state violates Flyway V24 atomic trigger rules
            logger.error("Corrupt override state detected on review {}: reason={}, user={}, at={}", reviewId, hasReason, hasUser, hasAt);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CORRUPT_OVERRIDE_STATE",
                    "Database override fields are partially populated or corrupt for CodeReview: " + reviewId);
        }

        // Step 7: Perform SQL Aggregations for Findings
        long totalCount = reviewFindingRepository.countTotalFindingsByReviewId(reviewId);
        long criticalUnmitigated = reviewFindingRepository.countUnmitigatedFindingsBySeverityAndReviewId(reviewId, FindingSeverity.CRITICAL);
        long highUnmitigated = reviewFindingRepository.countUnmitigatedFindingsBySeverityAndReviewId(reviewId, FindingSeverity.HIGH);
        long mediumUnmitigated = reviewFindingRepository.countUnmitigatedFindingsBySeverityAndReviewId(reviewId, FindingSeverity.MEDIUM);
        long lowUnmitigated = reviewFindingRepository.countUnmitigatedFindingsBySeverityAndReviewId(reviewId, FindingSeverity.LOW);
        long mitigatedCount = reviewFindingRepository.countMitigatedFindingsByReviewId(reviewId);

        boolean exceedsMaxFindingLimit = totalCount > 1000;

        // Step 8: Bounded Detailed Violation List Retrieval (Capped at 50 items)
        List<ReviewFinding> violationEntities = reviewFindingRepository.findBlockingViolationsByReviewId(reviewId, PageRequest.of(0, 50));
        List<PolicyViolationDto> policyViolations = violationEntities.stream()
                .map(f -> new PolicyViolationDto(
                        f.getId(),
                        f.getCweId(),
                        f.getSeverity(),
                        f.getStatus(),
                        f.getConfidence(),
                        preLlmSecretScanner.redactSecrets(f.getTitle()),
                        preLlmSecretScanner.redactSecrets(f.getDescription()),
                        f.getFilePath(),
                        f.getStartLine(),
                        f.getEndLine()
                ))
                .toList();

        // Step 9: Determine Policy Compliance Posture
        long blockingUnmitigatedCount = criticalUnmitigated + highUnmitigated;
        PolicyComplianceStatus complianceStatus;

        if (isExempted) {
            complianceStatus = PolicyComplianceStatus.EXEMPTED;
        } else if (blockingUnmitigatedCount == 0) {
            complianceStatus = PolicyComplianceStatus.PASSED;
        } else {
            complianceStatus = PolicyComplianceStatus.FAILED;
        }

        logger.info("Evaluated policy compliance for review {} in project {}: status={}, critical={}, high={}, total={}",
                reviewId, projectId, complianceStatus, criticalUnmitigated, highUnmitigated, totalCount);

        return new SecurityPolicyComplianceDto(
                review.getId(),
                projectId,
                repositoryId,
                taskId,
                complianceStatus,
                review.getPolicyVersion(),
                review.getValidatorVersion(),
                criticalUnmitigated,
                highUnmitigated,
                mediumUnmitigated,
                lowUnmitigated,
                mitigatedCount,
                totalCount,
                isExempted,
                isExempted ? overrideReason : null,
                isExempted ? overrideUser.getId() : null,
                isExempted ? overrideAt : null,
                exceedsMaxFindingLimit,
                policyViolations
        );
    }
}
