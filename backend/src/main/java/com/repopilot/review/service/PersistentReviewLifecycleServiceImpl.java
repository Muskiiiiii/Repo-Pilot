package com.repopilot.review.service;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.change.repository.CodeChangeRepository;
import com.repopilot.change.service.DiffGeneratorService;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 13 Slice 4 Persistent Review Lifecycle Service Implementation.
 * Manages persistent review records, state transitions, TOCTOU re-verification, atomic finding persistence, and IDOR protection.
 */
@Service
public class PersistentReviewLifecycleServiceImpl implements PersistentReviewLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(PersistentReviewLifecycleServiceImpl.class);

    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectRepository projectRepository;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeChangeRepository codeChangeRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final ReviewContextService reviewContextService;
    private final AiInputHashService aiInputHashService;
    private final ReviewOrchestrationService reviewOrchestrationService;
    private final DiffGeneratorService diffGeneratorService;
    private final PreLlmSecretScanner preLlmSecretScanner;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public PersistentReviewLifecycleServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            ProjectRepository projectRepository,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeChangeRepository codeChangeRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            ReviewContextService reviewContextService,
            AiInputHashService aiInputHashService,
            ReviewOrchestrationService reviewOrchestrationService,
            DiffGeneratorService diffGeneratorService,
            PreLlmSecretScanner preLlmSecretScanner,
            TransactionTemplate transactionTemplate) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectRepository = projectRepository;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeChangeRepository = codeChangeRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.reviewContextService = reviewContextService;
        this.aiInputHashService = aiInputHashService;
        this.reviewOrchestrationService = reviewOrchestrationService;
        this.diffGeneratorService = diffGeneratorService;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    @Transactional
    public CodeReview createReviewRecord(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            ReviewMode reviewMode,
            User requester) {

        final ReviewMode mode = reviewMode != null ? reviewMode : ReviewMode.CODE_CHANGE_REVIEW;

        // 1. Authoritative Hierarchy & Context Resolution
        ReviewContext context = reviewContextService.buildReviewContext(projectId, repositoryId, taskId, codeChangeId, mode, requester);

        String sourceContextHash = context.reviewContextHash();
        CodeChange codeChange = null;
        if (codeChangeId != null) {
            codeChange = codeChangeRepository.findById(codeChangeId).orElse(null);
        }

        // 2. Compute Canonical AI Payload Hash
        String redactedDiff = preLlmSecretScanner.redactSecrets(context.unifiedDiff() != null ? context.unifiedDiff() : "");
        String sanitizedTitle = preLlmSecretScanner.redactSecrets(context.filePath() != null ? context.filePath() : "TASK_SCOPE");
        AiReviewPayload payload = new AiReviewPayload(
                projectId, repositoryId, taskId, codeChangeId,
                mode.name(), redactedDiff, sanitizedTitle, List.of(sanitizedTitle),
                "p2026.1", "gemini-2.5-pro"
        );
        String aiInputHash = aiInputHashService.computeAiInputHash(payload);

        // 3. Idempotency Check: Existing Review for (codeChangeId, sourceContextHash, mode, aiInputHash)
        if (codeChangeId != null) {
            Optional<CodeReview> existing = codeReviewRepository.findByCodeChangeIdAndReviewContextHashAndReviewModeAndAiInputHash(
                    codeChangeId, sourceContextHash, mode, aiInputHash
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
        Repository repository = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository not found"));
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found"));

        // 4. Instantiate and Persist PENDING Record
        CodeReview review = new CodeReview();
        review.setProject(project);
        review.setRepository(repository);
        review.setTask(task);
        review.setCodeChange(codeChange);
        review.setReviewMode(mode);
        review.setStatus(ReviewStatus.PENDING);
        review.setSummary("Review initialized in PENDING state");
        review.setOverallRiskScore("LOW");
        review.setReviewContextHash(sourceContextHash);
        review.setAiInputHash(aiInputHash);
        review.setModelIdentifier("gemini-2.5-pro");
        review.setPromptVersion("p2026.1");
        review.setCreatedAt(Instant.now());

        try {
            return codeReviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // Concurrent creation race -> return winning record once parallel transaction commits
            for (int attempt = 0; attempt < 5; attempt++) {
                if (codeChangeId != null) {
                    Optional<CodeReview> winner = codeReviewRepository.findByCodeChangeIdAndReviewContextHashAndReviewModeAndAiInputHash(
                            codeChangeId, sourceContextHash, mode, aiInputHash
                    );
                    if (winner.isPresent()) {
                        return winner.get();
                    }
                } else {
                    List<CodeReview> winners = codeReviewRepository.findByProjectIdAndRepositoryIdAndTaskId(projectId, repositoryId, taskId);
                    if (!winners.isEmpty()) {
                        return winners.get(0);
                    }
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            throw e;
        }
    }

    @Override
    public CodeReviewResultDto executePersistentReview(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID codeChangeId,
            ReviewMode reviewMode,
            User requester) {

        final ReviewMode mode = reviewMode != null ? reviewMode : ReviewMode.CODE_CHANGE_REVIEW;

        // 1. Create or Obtain Persistent Review Record
        CodeReview review;
        try {
            review = createReviewRecord(projectId, repositoryId, taskId, codeChangeId, mode, requester);
        } catch (ApiException e) {
            if ("REJECTED_CONTEXT_TOO_LARGE".equals(e.getCode())) {
                return handleContextTooLargeRejection(projectId, repositoryId, taskId, codeChangeId, mode, e.getMessage());
            }
            throw e;
        }

        // Return immediately if review is already in terminal state (Idempotent execution skip)
        if (review.getStatus().isTerminal()) {
            return toResultDto(review);
        }

        // 2. Transition State: PENDING -> RUNNING with lock
        transitionState(review.getId(), ReviewStatus.RUNNING, "Review execution running");

        // 3. Invoke Slice 3 Controlled Orchestration Boundary
        AiReviewOrchestrationResult orchestrationResult = reviewOrchestrationService.orchestrateReview(
                projectId, repositoryId, taskId, codeChangeId, mode, requester
        );

        // 4. TOCTOU Context & Provenance Re-Verification
        ReviewContext currentContext = reviewContextService.buildReviewContext(projectId, repositoryId, taskId, codeChangeId, mode, requester);
        String reverifiedHash = currentContext.reviewContextHash();

        if (!reverifiedHash.equals(review.getReviewContextHash())) {
            // Context mutated during execution -> transition to STALE and discard findings
            logger.warn("TOCTOU context mismatch for review {}: original={}, current={}", review.getId(), review.getReviewContextHash(), reverifiedHash);
            return finalizeStaleReview(review.getId(), "CodeChange modified during review execution (TOCTOU boundary violation). Review marked STALE.");
        }

        // 5. Finalize Atomic Transaction (Status + Findings)
        return finalizeReviewAtomic(review.getId(), orchestrationResult);
    }

    @Override
    @Transactional(readOnly = true)
    public CodeReviewResultDto getReviewResult(UUID reviewId, UUID projectId, User requester) {
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, requester);
        projectAuthorizationService.requireMinimumRole(project, requester, ProjectRole.VIEWER);

        CodeReview review = codeReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));

        if (!review.getProject().getId().equals(projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Review does not belong to specified project");
        }

        return toResultDto(review);
    }

    private void transitionState(UUID reviewId, ReviewStatus newStatus, String newSummary) {
        transactionTemplate.executeWithoutResult(status -> {
            CodeReview r = codeReviewRepository.findByIdWithLock(reviewId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));
            validateStateTransition(r.getStatus(), newStatus);
            r.setStatus(newStatus);
            if (newSummary != null) {
                r.setSummary(preLlmSecretScanner.redactSecrets(newSummary));
            }
            codeReviewRepository.save(r);
        });
    }

    private CodeReviewResultDto finalizeStaleReview(UUID reviewId, String summary) {
        return transactionTemplate.execute(status -> {
            CodeReview r = codeReviewRepository.findByIdWithLock(reviewId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));
            if (r.getStatus().isTerminal()) {
                return toResultDto(r);
            }
            validateStateTransition(r.getStatus(), ReviewStatus.STALE);
            r.setStatus(ReviewStatus.STALE);
            r.setSummary(preLlmSecretScanner.redactSecrets(summary));
            r.setCompletedAt(Instant.now());
            codeReviewRepository.save(r);
            return toResultDto(r);
        });
    }

    private CodeReviewResultDto handleContextTooLargeRejection(UUID projectId, UUID repositoryId, UUID taskId, UUID codeChangeId, ReviewMode mode, String reason) {
        return transactionTemplate.execute(status -> {
            Project project = projectRepository.findById(projectId).orElse(null);
            Repository repo = repositoryRepository.findById(repositoryId).orElse(null);
            Task task = taskRepository.findById(taskId).orElse(null);
            CodeChange cc = codeChangeId != null ? codeChangeRepository.findById(codeChangeId).orElse(null) : null;

            CodeReview r = new CodeReview();
            r.setProject(project);
            r.setRepository(repo);
            r.setTask(task);
            r.setCodeChange(cc);
            r.setReviewMode(mode);
            r.setStatus(ReviewStatus.REJECTED_CONTEXT_TOO_LARGE);
            r.setSummary(preLlmSecretScanner.redactSecrets("Context limit exceeded: " + reason));
            r.setOverallRiskScore("HIGH");
            r.setReviewContextHash("CONTEXT_TOO_LARGE");
            r.setAiInputHash("N/A");
            r.setModelIdentifier("gemini-2.5-pro");
            r.setPromptVersion("p2026.1");
            r.setCreatedAt(Instant.now());
            r.setCompletedAt(Instant.now());
            r = codeReviewRepository.save(r);
            return toResultDto(r);
        });
    }

    private CodeReviewResultDto finalizeReviewAtomic(UUID reviewId, AiReviewOrchestrationResult orchestrationResult) {
        return transactionTemplate.execute(status -> {
            CodeReview review = codeReviewRepository.findByIdWithLock(reviewId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found"));

            if (review.getStatus().isTerminal()) {
                // If another worker already finalized this review, return current terminal state without re-persisting!
                return toResultDto(review);
            }

            if (orchestrationResult.status() == ReviewStatus.FAILED) {
                validateStateTransition(review.getStatus(), ReviewStatus.FAILED);
                review.setStatus(ReviewStatus.FAILED);
                review.setSummary(preLlmSecretScanner.redactSecrets(orchestrationResult.failureReason()));
                review.setCompletedAt(Instant.now());
                review.setExecutionTimeMs(orchestrationResult.executionTimeMs());
                codeReviewRepository.save(review);
                return toResultDto(review);
            }

            validateStateTransition(review.getStatus(), ReviewStatus.COMPLETED);
            review.setStatus(ReviewStatus.COMPLETED);
            review.setSummary(preLlmSecretScanner.redactSecrets(orchestrationResult.summary()));
            review.setOverallRiskScore(orchestrationResult.overallRiskScore().name());
            review.setExecutionTimeMs(orchestrationResult.executionTimeMs());
            review.setCompletedAt(Instant.now());

            // Atomic Finding Persistence with Duplicate Protection
            List<ReviewFinding> existingFindings = reviewFindingRepository.findByReview(review);
            if (existingFindings.isEmpty()) {
                List<ReviewFinding> findingsToSave = new ArrayList<>();
                List<AiReviewerFindingDto> findingDtos = orchestrationResult.findings();
                if (findingDtos != null) {
                    for (AiReviewerFindingDto dto : findingDtos) {
                        ReviewFinding f = new ReviewFinding();
                        f.setReview(review);
                        f.setProject(review.getProject());
                        f.setRepository(review.getRepository());
                        f.setTask(review.getTask());
                        f.setCodeChange(review.getCodeChange());
                        f.setFindingType(dto.findingType());
                        f.setCategory(dto.category() != null ? dto.category() : "GENERAL");
                        f.setCweId(dto.cweId());
                        f.setSeverity(dto.severity());
                        f.setConfidence(dto.confidence());
                        f.setTitle(preLlmSecretScanner.redactSecrets(dto.title()));
                        f.setDescription(preLlmSecretScanner.redactSecrets(dto.description()));
                        f.setFilePath(dto.filePath());
                        f.setStartLine(dto.startLine());
                        f.setEndLine(dto.endLine());
                        f.setSide(dto.side());
                        f.setContentHash(computeFindingContentHash(dto));
                        f.setFingerprint(computeFindingFingerprint(dto));
                        f.setRemediationGuidance(dto.remediation() != null ? preLlmSecretScanner.redactSecrets(dto.remediation()) : null);
                        f.setCreatedAt(Instant.now());
                        f.setUpdatedAt(Instant.now());
                        findingsToSave.add(f);
                    }
                    reviewFindingRepository.saveAll(findingsToSave);
                }
            }

            review = codeReviewRepository.save(review);
            return toResultDto(review);
        });
    }

    private void validateStateTransition(ReviewStatus current, ReviewStatus target) {
        if (current == target) {
            return;
        }
        if (current.isTerminal()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ILLEGAL_STATE_TRANSITION",
                    "Cannot transition CodeReview from terminal state " + current + " to " + target);
        }
        if (current == ReviewStatus.PENDING) {
            if (target == ReviewStatus.RUNNING || target == ReviewStatus.FAILED || target == ReviewStatus.STALE || target == ReviewStatus.REJECTED_CONTEXT_TOO_LARGE) {
                return;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "ILLEGAL_STATE_TRANSITION",
                    "Cannot transition CodeReview from PENDING to " + target);
        }
        if (current == ReviewStatus.RUNNING) {
            if (target == ReviewStatus.COMPLETED || target == ReviewStatus.FAILED || target == ReviewStatus.STALE) {
                return;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "ILLEGAL_STATE_TRANSITION",
                    "Cannot transition CodeReview from RUNNING to " + target);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "ILLEGAL_STATE_TRANSITION",
                "Illegal state transition from " + current + " to " + target);
    }

    private CodeReviewResultDto toResultDto(CodeReview review) {
        List<ReviewFinding> findings = reviewFindingRepository.findByReview(review);
        List<ValidatedFindingCandidate> findingCandidates = findings.stream().map(f -> new ValidatedFindingCandidate(
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
                findingCandidates.size(),
                findingCandidates.size(),
                0,
                findingCandidates
        );
    }

    private String computeFindingContentHash(AiReviewerFindingDto dto) {
        String raw = (dto.title() != null ? dto.title() : "") + ":" +
                (dto.description() != null ? dto.description() : "") + ":" +
                (dto.filePath() != null ? dto.filePath() : "") + ":" +
                dto.startLine() + ":" + dto.endLine();
        return diffGeneratorService.computeSha256Hash(raw);
    }

    private String computeFindingFingerprint(AiReviewerFindingDto dto) {
        String raw = (dto.category() != null ? dto.category() : "") + ":" +
                (dto.filePath() != null ? dto.filePath() : "") + ":" +
                dto.startLine() + ":" +
                (dto.severity() != null ? dto.severity().name() : "");
        return diffGeneratorService.computeSha256Hash(raw);
    }
}
