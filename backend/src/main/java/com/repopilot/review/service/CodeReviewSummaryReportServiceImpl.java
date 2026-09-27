package com.repopilot.review.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.review.entity.FindingSeverity;
import com.repopilot.review.entity.ReviewFinding;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class CodeReviewSummaryReportServiceImpl implements CodeReviewSummaryReportService {

    private static final Logger logger = LoggerFactory.getLogger(CodeReviewSummaryReportServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final CodeReviewApprovalGateService codeReviewApprovalGateService;
    private final ReviewContextService reviewContextService;
    private final PreLlmSecretScanner preLlmSecretScanner;

    @Autowired
    public CodeReviewSummaryReportServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            CodeReviewApprovalGateService codeReviewApprovalGateService,
            ReviewContextService reviewContextService,
            PreLlmSecretScanner preLlmSecretScanner) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.taskRepository = taskRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.codeReviewApprovalGateService = codeReviewApprovalGateService;
        this.reviewContextService = reviewContextService;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
    }

    @Override
    @Transactional(readOnly = true)
    public CodeReviewSummaryReportDto generateReviewSummary(
            UUID projectId,
            UUID taskId,
            UUID codeChangeId,
            User requester) {

        if (requester == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User must be authenticated to request review summary");
        }

        // 1. Project Authorization (MEMBER role required)
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.MEMBER);

        // 2. Task Validation
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found"));
        if (!task.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found");
        }

        // 3. Server-Derived repositoryId
        Repository repo = task.getRepository();
        if (repo == null || repo.getProject() == null || !repo.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found");
        }
        UUID repositoryId = repo.getId();

        // 4. CodeChange Resolution
        CodeChange selectedChange;
        boolean isActive;

        if (codeChangeId != null) {
            selectedChange = codeChangeRepository.findById(codeChangeId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found"));
            if (!selectedChange.getTask().getId().equals(taskId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found");
            }
            if (selectedChange.getRepository() == null || !selectedChange.getRepository().getId().equals(repositoryId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found");
            }
            String st = selectedChange.getStatus();
            isActive = st != null && !"REJECTED".equalsIgnoreCase(st) && !"VALIDATION_FAILED".equalsIgnoreCase(st);
        } else {
            List<CodeChange> activeChanges = codeChangeRepository.findActiveCodeChangesForTaskAndRepository(taskId, repositoryId, Pageable.ofSize(1));
            if (activeChanges.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NO_ACTIVE_CODE_CHANGE", "No active CodeChange found for specified task");
            }
            selectedChange = activeChanges.get(0);
            isActive = true;
        }

        String codeChangeStatus = selectedChange.getStatus();
        boolean isAlreadyApproved = "APPROVED".equalsIgnoreCase(codeChangeStatus);

        // 5. Review Selection
        List<CodeReview> completedReviews = codeReviewRepository.findCompletedReviewsForCodeChange(
                projectId, repositoryId, taskId, selectedChange.getId(), Pageable.ofSize(1)
        );
        if (completedReviews.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "No completed CodeReview found for specified CodeChange");
        }
        CodeReview review = completedReviews.get(0);

        if (!review.getCodeChange().getId().equals(selectedChange.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVIEW_ENTITY_MISMATCH", "Review does not belong to selected CodeChange");
        }
        if (!review.getTask().getId().equals(taskId) || !review.getProject().getId().equals(projectId) || !review.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVIEW_ENTITY_MISMATCH", "Review does not belong to specified project/repository/task hierarchy");
        }

        // 6. Context Staleness Check (Observational Only)
        ReviewContext currentContext = null;
        try {
            currentContext = reviewContextService.buildReviewContext(
                    projectId, repositoryId, taskId, selectedChange.getId(), review.getReviewMode(), requester
            );
        } catch (Exception e) {
            logger.warn("Could not build current ReviewContext for staleness check: {}", e.getMessage());
        }
        String currentContextHash = currentContext != null ? currentContext.reviewContextHash() : "";
        String reviewContextHash = review.getReviewContextHash();
        boolean isContextStale = currentContext == null || currentContext.isStale() ||
                reviewContextHash == null || !reviewContextHash.equals(currentContextHash);

        // 7. Delegate Eligibility to Slice 7 (Single Source of Truth)
        CodeReviewEligibilityResultDto eligibility = codeReviewApprovalGateService.evaluateReviewEligibilityForApproval(
                projectId, repositoryId, taskId, selectedChange.getId(), review.getId(), requester
        );

        boolean eligibleForApproval = eligibility.eligible();
        boolean eligibleForNewApproval = !isAlreadyApproved && isActive && eligibleForApproval;

        String eligibilityReason;
        if (isAlreadyApproved) {
            eligibilityReason = "CodeChange has already been APPROVED";
        } else if (!isActive) {
            eligibilityReason = "CodeChange is inactive (" + codeChangeStatus + ")";
        } else {
            eligibilityReason = eligibility.reason();
        }

        // 8. Admin Override DTO
        boolean hasAdminOverride = eligibility.hasAdminOverride();
        AdminReviewOverrideResponseDto adminOverrideDto = null;
        if (hasAdminOverride && review.getAdminOverrideReason() != null) {
            adminOverrideDto = new AdminReviewOverrideResponseDto(
                    review.getId(),
                    projectId,
                    repositoryId,
                    taskId,
                    selectedChange.getId(),
                    review.getStatus(),
                    review.getAdminOverrideReason(),
                    review.getAdminOverrideByUser() != null ? review.getAdminOverrideByUser().getId() : null,
                    review.getAdminOverrideByUser() != null ? review.getAdminOverrideByUser().getName() : "Admin",
                    review.getAdminOverrideAt()
            );
        }

        // 9. DB-Side Severity Aggregation Query
        List<ReviewFindingRepository.FindingSeverityCountProjection> counts =
                reviewFindingRepository.countFindingsBySeverityForReview(review.getId());

        long criticalCount = 0;
        long highCount = 0;
        long mediumCount = 0;
        long lowCount = 0;
        long infoCount = 0;

        for (ReviewFindingRepository.FindingSeverityCountProjection p : counts) {
            if (p.getSeverity() != null) {
                switch (p.getSeverity()) {
                    case CRITICAL -> criticalCount = p.getCount();
                    case HIGH -> highCount = p.getCount();
                    case MEDIUM -> mediumCount = p.getCount();
                    case LOW -> lowCount = p.getCount();
                    case INFO -> infoCount = p.getCount();
                }
            }
        }
        long totalCount = criticalCount + highCount + mediumCount + lowCount + infoCount;
        FindingSummaryDto findingsSummary = new FindingSummaryDto(
                totalCount, criticalCount, highCount, mediumCount, lowCount, infoCount
        );

        // 10. DB-Side Bounded Snippet Query (Limit 50) & Secret Redaction
        List<ReviewFinding> rawSnippets = reviewFindingRepository.findBoundedSnippetsForReview(review.getId(), Pageable.ofSize(50));
        List<ReviewFindingSnippetDto> snippetDtos = new ArrayList<>(rawSnippets.size());

        for (ReviewFinding f : rawSnippets) {
            String redactedTitle = preLlmSecretScanner.redactSecrets(f.getTitle() != null ? f.getTitle() : "");
            String redactedDesc = preLlmSecretScanner.redactSecrets(f.getDescription() != null ? f.getDescription() : "");

            snippetDtos.add(new ReviewFindingSnippetDto(
                    f.getId(),
                    f.getFindingType(),
                    f.getCategory(),
                    f.getCweId(),
                    f.getSeverity(),
                    f.getConfidence(),
                    redactedTitle,
                    redactedDesc,
                    f.getFilePath(),
                    f.getStartLine(),
                    f.getEndLine()
            ));
        }

        int snippetCount = snippetDtos.size();
        boolean snippetsCapped = totalCount > 50;

        return new CodeReviewSummaryReportDto(
                projectId,
                repositoryId,
                taskId,
                selectedChange.getId(),
                codeChangeStatus,
                isActive,
                isAlreadyApproved,
                review.getId(),
                review.getStatus(),
                review.getOverallRiskScore(),
                eligibleForApproval,
                eligibleForNewApproval,
                eligibilityReason,
                isContextStale,
                reviewContextHash,
                currentContextHash,
                findingsSummary,
                hasAdminOverride,
                adminOverrideDto,
                snippetCount,
                snippetsCapped,
                snippetDtos,
                review.getCreatedAt() != null ? review.getCreatedAt() : Instant.now()
        );
    }
}
