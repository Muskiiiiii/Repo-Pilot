package com.repopilot.review.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.entity.Repository;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.*;
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

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CodeReviewOrchestrationServiceImpl implements CodeReviewOrchestrationService {

    private static final Logger logger = LoggerFactory.getLogger(CodeReviewOrchestrationServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectRepository projectRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final PersistentReviewLifecycleService persistentReviewLifecycleService;
    private final ReviewWorkerExecutionService reviewWorkerExecutionService;

    @Autowired
    public CodeReviewOrchestrationServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            ProjectRepository projectRepository,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            PersistentReviewLifecycleService persistentReviewLifecycleService,
            ReviewWorkerExecutionService reviewWorkerExecutionService) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectRepository = projectRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.persistentReviewLifecycleService = persistentReviewLifecycleService;
        this.reviewWorkerExecutionService = reviewWorkerExecutionService;
    }

    @Override
    public CodeReviewResultDto executeCodeReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            ReviewMode reviewMode,
            String idempotencyKey,
            User requester) {

        final ReviewMode finalReviewMode = reviewMode != null ? reviewMode : ReviewMode.CODE_CHANGE_REVIEW;

        // 1. Authorization & Hierarchy Verification
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

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

        if (codeChangeId != null) {
            CodeChange cc = codeChangeRepository.findById(codeChangeId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found"));
            if (!cc.getTask().getId().equals(taskId) || !cc.getRepository().getId().equals(repositoryId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "CodeChange does not belong to specified task/repository");
            }
        }

        // 2. Authoritative Persistent Lifecycle Record (Handles 4-Tuple Idempotency)
        CodeReview record = persistentReviewLifecycleService.createReviewRecord(
                projectId, repositoryId, taskId, codeChangeId, finalReviewMode, requester
        );

        if (record.getStatus().isTerminal()) {
            logger.info("Returning existing terminal CodeReview id={} status={}", record.getId(), record.getStatus());
            return mapToResultDto(record);
        }

        // 3. Worker Claim & Execution Pipeline
        String workerId = "http-worker-" + UUID.randomUUID().toString().substring(0, 8);
        Optional<CodeReviewClaimDto> claimOpt = reviewWorkerExecutionService.claimSpecificReview(record.getId(), workerId, Duration.ofMinutes(5));

        if (claimOpt.isEmpty()) {
            // Race lost or already claimed by concurrent execution: return latest DB record
            CodeReview activeRecord = codeReviewRepository.findById(record.getId()).orElseThrow();
            return mapToResultDto(activeRecord);
        }

        // Execute Claimed Review with Fencing & TOCTOU Verification
        return reviewWorkerExecutionService.executeClaimedReview(claimOpt.get(), requester);
    }

    @Override
    public CodeReviewResultDto getCodeReviewResult(UUID reviewId, User requester) {
        if (reviewId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_ID", "reviewId cannot be null");
        }
        CodeReview review = codeReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));

        Project project = projectAuthorizationService.getAuthorizedProject(review.getProject().getId(), requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        return mapToResultDto(review);
    }

    @Override
    public List<CodeReviewResultDto> getCodeReviewsForCodeChange(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId, User requester) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

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

        CodeChange targetChange = codeChangeRepository.findById(codeChangeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CODE_CHANGE_NOT_FOUND", "CodeChange not found"));
        if (!targetChange.getTask().getId().equals(taskId) || !targetChange.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "CodeChange does not belong to specified task/repository");
        }

        List<CodeReview> reviews = codeReviewRepository.findByProjectIdAndRepositoryIdAndTaskIdAndCodeChangeId(projectId, repositoryId, taskId, codeChangeId);
        return reviews.stream().map(this::mapToResultDto).collect(Collectors.toList());
    }

    @Override
    public List<CodeReviewResultDto> getCodeReviewsForTask(UUID projectId, UUID repositoryId, UUID taskId, User requester) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

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

        List<CodeReview> reviews = codeReviewRepository.findByProjectIdAndRepositoryIdAndTaskId(projectId, repositoryId, taskId);
        return reviews.stream().map(this::mapToResultDto).collect(Collectors.toList());
    }

    private CodeReviewResultDto mapToResultDto(CodeReview review) {
        List<ReviewFinding> findings = reviewFindingRepository.findByReview(review);
        List<ValidatedFindingCandidate> candidates = findings.stream().map(f -> new ValidatedFindingCandidate(
                f.getFindingType(),
                f.getCategory(),
                f.getCweId(),
                f.getSeverity(),
                f.getConfidence(),
                f.getTitle(),
                f.getDescription(),
                f.getFilePath(),
                f.getStartLine(),
                f.getEndLine(),
                f.getSide(),
                "",
                f.getRemediationGuidance(),
                f.getContentHash(),
                f.getFingerprint(),
                false
        )).collect(Collectors.toList());

        return new CodeReviewResultDto(
                review.getId(),
                review.getProject().getId(),
                review.getRepository().getId(),
                review.getTask().getId(),
                review.getCodeChange() != null ? review.getCodeChange().getId() : null,
                review.getReviewMode(),
                review.getStatus(),
                review.getSummary(),
                review.getOverallRiskScore(),
                review.getReviewContextHash(),
                review.getModelIdentifier(),
                review.getExecutionTimeMs(),
                review.getCreatedAt(),
                review.getCompletedAt(),
                candidates.size(),
                candidates.size(),
                0,
                candidates
        );
    }
}
