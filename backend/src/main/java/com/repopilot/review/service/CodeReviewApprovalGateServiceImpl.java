package com.repopilot.review.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.CodeReviewEligibilityResultDto;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class CodeReviewApprovalGateServiceImpl implements CodeReviewApprovalGateService {

    private static final Logger logger = LoggerFactory.getLogger(CodeReviewApprovalGateServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final ReviewContextService reviewContextService;

    @Autowired
    public CodeReviewApprovalGateServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            ReviewContextService reviewContextService) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.reviewContextService = reviewContextService;
    }

    @Override
    @Transactional(readOnly = true)
    public CodeReviewEligibilityResultDto evaluateReviewEligibilityForApproval(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            UUID requestedReviewId,
            User requester) {

        if (requester == null) {
            return CodeReviewEligibilityResultDto.ineligible("Authentication required");
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

        CodeChange codeChange = codeChangeRepository.findById(codeChangeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found"));
        if (!codeChange.getTask().getId().equals(taskId) || !codeChange.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "CodeChange does not belong to specified task/repository");
        }

        // 3. Build Current Canonical ReviewContext & Context Hash
        ReviewContext currentContext = reviewContextService.buildReviewContext(
                projectId, repositoryId, taskId, codeChangeId, ReviewMode.CODE_CHANGE_REVIEW, requester
        );
        if (currentContext == null || currentContext.isStale()) {
            return CodeReviewEligibilityResultDto.ineligible("CodeChange context is stale or invalid");
        }
        String currentContextHash = currentContext.reviewContextHash();

        // 4. Candidate Review Resolution
        CodeReview targetReview = null;
        if (requestedReviewId != null) {
            Optional<CodeReview> requestedOpt = codeReviewRepository.findById(requestedReviewId);
            if (requestedOpt.isEmpty()) {
                return CodeReviewEligibilityResultDto.ineligible("Requested CodeReview not found: " + requestedReviewId);
            }
            targetReview = requestedOpt.get();
        } else {
            List<CodeReview> eligibleReviews = codeReviewRepository.findEligibleCompletedReviews(
                    projectId, repositoryId, taskId, codeChangeId, currentContextHash
            );
            if (!eligibleReviews.isEmpty()) {
                targetReview = eligibleReviews.get(0);
            } else {
                // Fallback lookup by codeChangeId to provide accurate diagnostic reason
                List<CodeReview> allReviewsForChange = codeReviewRepository.findByCodeChangeId(codeChangeId);
                if (allReviewsForChange.isEmpty()) {
                    return CodeReviewEligibilityResultDto.ineligible("No CodeReview record exists for this CodeChange");
                }
                CodeReview latest = allReviewsForChange.get(allReviewsForChange.size() - 1);
                if (latest.getStatus() != ReviewStatus.COMPLETED) {
                    return CodeReviewEligibilityResultDto.ineligible(
                            "Latest CodeReview is not in COMPLETED status (current status: " + latest.getStatus() + ")",
                            latest.getId(), latest.getStatus(), latest.getReviewContextHash(), 0, 0, 0
                    );
                }
                return CodeReviewEligibilityResultDto.ineligible(
                        "CodeChange context has drifted since the last completed review (stale reviewContextHash)",
                        latest.getId(), latest.getStatus(), latest.getReviewContextHash(), 0, 0, 0
                );
            }
        }

        // 5. Hierarchy and Status Check on Target Review
        if (!targetReview.getProject().getId().equals(projectId) ||
            !targetReview.getRepository().getId().equals(repositoryId) ||
            !targetReview.getTask().getId().equals(taskId) ||
            targetReview.getCodeChange() == null ||
            !targetReview.getCodeChange().getId().equals(codeChangeId)) {
            return CodeReviewEligibilityResultDto.ineligible("CodeReview hierarchy mismatch against specified change/task/repository");
        }

        if (targetReview.getStatus() != ReviewStatus.COMPLETED) {
            return CodeReviewEligibilityResultDto.ineligible(
                    "CodeReview status is not COMPLETED (status: " + targetReview.getStatus() + ")",
                    targetReview.getId(), targetReview.getStatus(), targetReview.getReviewContextHash(), 0, 0, 0
            );
        }

        if (!currentContextHash.equalsIgnoreCase(targetReview.getReviewContextHash())) {
            return CodeReviewEligibilityResultDto.ineligible(
                    "CodeChange content or diff has changed since review was completed (reviewContextHash mismatch)",
                    targetReview.getId(), targetReview.getStatus(), targetReview.getReviewContextHash(), 0, 0, 0
            );
        }

        // 6. Finding Severity Analysis & Admin Override Check
        List<ReviewFinding> findings = reviewFindingRepository.findByReview(targetReview);
        int criticalCount = 0;
        int highCount = 0;
        for (ReviewFinding f : findings) {
            if (f.getStatus() == FindingStatus.OPEN || f.getStatus() == FindingStatus.ACKNOWLEDGED) {
                if (f.getSeverity() == FindingSeverity.CRITICAL) {
                    criticalCount++;
                } else if (f.getSeverity() == FindingSeverity.HIGH) {
                    highCount++;
                }
            }
        }

        boolean hasAdminOverride = targetReview.getAdminOverrideReason() != null && !targetReview.getAdminOverrideReason().isBlank();

        if ((criticalCount > 0 || highCount > 0) && !hasAdminOverride) {
            return CodeReviewEligibilityResultDto.ineligible(
                    "CodeReview has " + criticalCount + " CRITICAL and " + highCount + " HIGH security findings and no Admin Override has been recorded",
                    targetReview.getId(), targetReview.getStatus(), targetReview.getReviewContextHash(), criticalCount, highCount, findings.size()
            );
        }

        return CodeReviewEligibilityResultDto.eligible(
                targetReview.getId(), targetReview.getStatus(), targetReview.getReviewContextHash(),
                hasAdminOverride, criticalCount, highCount, findings.size()
        );
    }
}
