package com.repopilot.review.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.security.service.PreLlmSecretScanner;
import com.repopilot.security.service.PreLlmSecretScannerImpl;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 13 Slice 5 Review Worker Execution Service Implementation.
 * Manages worker claim, fencing, heartbeat lease renewal, crash recovery, TOCTOU re-verification,
 * and fenced atomic result persistence.
 */
@Service
public class ReviewWorkerExecutionServiceImpl implements ReviewWorkerExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewWorkerExecutionServiceImpl.class);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(5);

    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final ReviewContextService reviewContextService;
    private final ReviewOrchestrationService reviewOrchestrationService;
    private final PreLlmSecretScanner preLlmSecretScanner;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ReviewWorkerExecutionServiceImpl(
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            ReviewContextService reviewContextService,
            ReviewOrchestrationService reviewOrchestrationService,
            PreLlmSecretScanner preLlmSecretScanner,
            TransactionTemplate transactionTemplate) {
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.reviewContextService = reviewContextService;
        this.reviewOrchestrationService = reviewOrchestrationService;
        this.preLlmSecretScanner = preLlmSecretScanner != null ? preLlmSecretScanner : new PreLlmSecretScannerImpl();
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<CodeReviewClaimDto> claimNextAvailableReview(String workerId, Duration leaseDuration) {
        List<UUID> claimableIds = discoverClaimableReviewIds(1);
        if (claimableIds.isEmpty()) {
            return Optional.empty();
        }
        return claimSpecificReview(claimableIds.get(0), workerId, leaseDuration);
    }

    @Override
    public Optional<CodeReviewClaimDto> claimSpecificReview(UUID reviewId, String workerId, Duration leaseDuration) {
        final Duration duration = leaseDuration != null ? leaseDuration : DEFAULT_LEASE_DURATION;
        final Instant now = Instant.now();
        final Instant expiresAt = now.plus(duration);
        final String fencingToken = UUID.randomUUID().toString();
        final String summary = "Claimed by worker " + workerId + " at " + now;

        Boolean claimed = transactionTemplate.execute(status -> {
            int updated = codeReviewRepository.claimReviewAtomic(
                    reviewId, workerId, fencingToken, expiresAt, now, summary,
                    ReviewStatus.PENDING, ReviewStatus.RUNNING
            );
            return updated > 0;
        });

        if (Boolean.TRUE.equals(claimed)) {
            CodeReview review = codeReviewRepository.findById(reviewId).orElseThrow();
            logger.info("Worker {} claimed review {} with fencingToken {}", workerId, reviewId, fencingToken);
            return Optional.of(new CodeReviewClaimDto(
                    review.getId(),
                    workerId,
                    fencingToken,
                    expiresAt,
                    now,
                    review.getProject().getId(),
                    review.getRepository().getId(),
                    review.getTask().getId(),
                    review.getCodeChange() != null ? review.getCodeChange().getId() : null,
                    review.getReviewMode()
            ));
        }

        logger.debug("Worker {} failed to claim review {} (race lost or not claimable)", workerId, reviewId);
        return Optional.empty();
    }

    @Override
    public boolean renewLease(UUID reviewId, String workerId, String leaseToken, Duration extensionDuration) {
        final Duration duration = extensionDuration != null ? extensionDuration : DEFAULT_LEASE_DURATION;
        final Instant now = Instant.now();
        final Instant newExpiresAt = now.plus(duration);

        Boolean renewed = transactionTemplate.execute(status -> {
            int updated = codeReviewRepository.renewLeaseAtomic(
                    reviewId, workerId, leaseToken, newExpiresAt, ReviewStatus.RUNNING
            );
            return updated > 0;
        });

        if (Boolean.TRUE.equals(renewed)) {
            logger.debug("Worker {} renewed lease for review {} until {}", workerId, reviewId, newExpiresAt);
            return true;
        }

        logger.warn("Worker {} FENCING REJECTION: failed to renew lease for review {} (stale token {})", workerId, reviewId, leaseToken);
        return false;
    }

    @Override
    public CodeReviewResultDto executeClaimedReview(CodeReviewClaimDto claim, User requester) {
        final Instant now = Instant.now();

        // 1. Pre-execution Fencing Verification: Confirm worker still owns lease
        CodeReview activeReview = codeReviewRepository.findActiveClaimWithFencing(
                claim.reviewId(), claim.workerId(), claim.leaseToken(), now, ReviewStatus.RUNNING
        ).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "FENCING_LEASE_EXPIRED",
                "Worker lease expired or stolen prior to review execution for review " + claim.reviewId()));

        // 2. Execute Slice 3 Controlled Orchestration Boundary
        AiReviewOrchestrationResult orchestrationResult = reviewOrchestrationService.orchestrateReview(
                claim.projectId(), claim.repositoryId(), claim.taskId(), claim.codeChangeId(), claim.reviewMode(), requester
        );

        // 3. TOCTOU & Context Provenance Verification
        ReviewContext currentContext = reviewContextService.buildReviewContext(
                claim.projectId(), claim.repositoryId(), claim.taskId(), claim.codeChangeId(), claim.reviewMode(), requester
        );
        String reverifiedHash = currentContext.reviewContextHash();

        if (!reverifiedHash.equals(activeReview.getReviewContextHash())) {
            logger.warn("TOCTOU context mismatch for claimed review {}: original={}, current={}", claim.reviewId(), activeReview.getReviewContextHash(), reverifiedHash);
            return finalizeStaleReviewFenced(claim, "CodeChange modified during worker execution (TOCTOU boundary violation). Review marked STALE.");
        }

        // 4. Fenced Atomic Finalization
        return finalizeReviewAtomicFenced(claim, orchestrationResult);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> discoverClaimableReviewIds(int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        List<CodeReview> reviews = codeReviewRepository.findClaimableOrExpiredReviews(Instant.now(), ReviewStatus.PENDING, ReviewStatus.RUNNING, page);
        return reviews.stream().map(CodeReview::getId).collect(Collectors.toList());
    }

    private CodeReviewResultDto finalizeStaleReviewFenced(CodeReviewClaimDto claim, String summary) {
        return transactionTemplate.execute(status -> {
            int updated = codeReviewRepository.finalizeReviewFenced(
                    claim.reviewId(), claim.workerId(), claim.leaseToken(),
                    ReviewStatus.STALE, preLlmSecretScanner.redactSecrets(summary),
                    "HIGH", null, Instant.now(), ReviewStatus.RUNNING
            );

            if (updated == 0) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FENCING_LEASE_LOST",
                        "Stale worker cannot mark review STALE; lease ownership lost for review " + claim.reviewId());
            }

            CodeReview r = codeReviewRepository.findById(claim.reviewId()).orElseThrow();
            return toResultDto(r);
        });
    }

    private CodeReviewResultDto finalizeReviewAtomicFenced(CodeReviewClaimDto claim, AiReviewOrchestrationResult orchestrationResult) {
        return transactionTemplate.execute(status -> {
            // Verify active lease ownership before terminal DB write
            Optional<CodeReview> currentOpt = codeReviewRepository.findActiveClaimWithFencing(
                    claim.reviewId(), claim.workerId(), claim.leaseToken(), Instant.now(), ReviewStatus.RUNNING
            );

            if (currentOpt.isEmpty()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FENCING_LEASE_LOST",
                        "Stale worker cannot finalize review; lease ownership lost for review " + claim.reviewId());
            }

            CodeReview review = currentOpt.get();

            // Pre-persistence context staleness check (TOCTOU protection)
            if (review.getCodeChange() != null) {
                User userForContext = review.getProject().getOwner();
                ReviewContext currentCtx = reviewContextService.buildReviewContext(
                        review.getProject().getId(), review.getRepository().getId(), review.getTask().getId(),
                        review.getCodeChange().getId(), review.getReviewMode(), userForContext
                );
                if (currentCtx == null || currentCtx.isStale() || !currentCtx.reviewContextHash().equalsIgnoreCase(review.getReviewContextHash())) {
                    codeReviewRepository.finalizeReviewFenced(
                            claim.reviewId(), claim.workerId(), claim.leaseToken(),
                            ReviewStatus.STALE, "Context became stale during review execution",
                            "HIGH", orchestrationResult.executionTimeMs(), Instant.now(), ReviewStatus.RUNNING
                    );
                    throw new ApiException(HttpStatus.BAD_REQUEST, "STALE_CONTEXT", "CodeChange context became stale during review execution");
                }
            }

            if (orchestrationResult.status() == ReviewStatus.FAILED) {
                int updated = codeReviewRepository.finalizeReviewFenced(
                        claim.reviewId(), claim.workerId(), claim.leaseToken(),
                        ReviewStatus.FAILED, preLlmSecretScanner.redactSecrets(orchestrationResult.failureReason()),
                        "HIGH", orchestrationResult.executionTimeMs(), Instant.now(), ReviewStatus.RUNNING
                );
                if (updated == 0) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "FENCING_LEASE_LOST", "Stale worker failed to finalize FAILED status");
                }
                return toResultDto(codeReviewRepository.findById(claim.reviewId()).orElseThrow());
            }

            // Target Status: COMPLETED
            int updated = codeReviewRepository.finalizeReviewFenced(
                    claim.reviewId(), claim.workerId(), claim.leaseToken(),
                    ReviewStatus.COMPLETED, preLlmSecretScanner.redactSecrets(orchestrationResult.summary()),
                    orchestrationResult.overallRiskScore().name(), orchestrationResult.executionTimeMs(), Instant.now(), ReviewStatus.RUNNING
            );

            if (updated == 0) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FENCING_LEASE_LOST",
                        "Stale worker failed to update review to COMPLETED for review " + claim.reviewId());
            }

            // Atomic Finding Persistence with Duplicate & Validation Checks
            CodeReview updatedReview = codeReviewRepository.findById(claim.reviewId()).orElseThrow();
            List<ReviewFinding> existingFindings = reviewFindingRepository.findByReview(updatedReview);
            if (existingFindings.isEmpty()) {
                List<ReviewFinding> findingsToSave = new ArrayList<>();
                List<AiReviewerFindingDto> findingDtos = orchestrationResult.findings();
                if (findingDtos != null) {
                    Set<String> seenFingerprints = new HashSet<>();
                    for (AiReviewerFindingDto dto : findingDtos) {
                        // Path traversal security rejection
                        if (dto.filePath() != null && (dto.filePath().startsWith("..") || dto.filePath().contains("../") || dto.filePath().contains("..\\"))) {
                            continue;
                        }
                        String fp = computeFindingFingerprint(dto);
                        if (seenFingerprints.contains(fp)) {
                            continue; // Deduplicate within batch
                        }
                        seenFingerprints.add(fp);

                        ReviewFinding f = new ReviewFinding();
                        f.setReview(updatedReview);
                        f.setProject(updatedReview.getProject());
                        f.setRepository(updatedReview.getRepository());
                        f.setTask(updatedReview.getTask());
                        f.setCodeChange(updatedReview.getCodeChange());
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
                        f.setFingerprint(fp);
                        f.setRemediationGuidance(dto.remediation() != null ? preLlmSecretScanner.redactSecrets(dto.remediation()) : null);
                        f.setCreatedAt(Instant.now());
                        f.setUpdatedAt(Instant.now());
                        findingsToSave.add(f);
                    }
                    reviewFindingRepository.saveAll(findingsToSave);
                }
            }

            return toResultDto(updatedReview);
        });
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
        return Integer.toHexString(Objects.hash(dto.filePath(), dto.startLine(), dto.endLine(), dto.title(), dto.description()));
    }

    private String computeFindingFingerprint(AiReviewerFindingDto dto) {
        return Integer.toHexString(Objects.hash(dto.category(), dto.cweId(), dto.filePath(), dto.startLine()));
    }
}
