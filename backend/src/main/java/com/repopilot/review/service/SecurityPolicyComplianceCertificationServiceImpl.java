package com.repopilot.review.service;

import com.repopilot.audit.entity.AuditLog;
import com.repopilot.audit.repository.AuditLogRepository;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.common.exception.ApiException;
import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.repository.ProjectRepository;
import com.repopilot.project.service.ProjectAuthorizationService;
import com.repopilot.repository.repository.RepositoryRepository;
import com.repopilot.review.dto.CanonicalFindingInput;
import com.repopilot.review.dto.CreatePolicyComplianceCertificateRequestDto;
import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.SecurityPolicyComplianceCertificateDto;
import com.repopilot.review.dto.SecurityPolicyComplianceDto;
import com.repopilot.review.entity.*;
import com.repopilot.review.repository.CodeReviewRepository;
import com.repopilot.review.repository.ComplianceCertificateRepository;
import com.repopilot.review.repository.ReviewFindingRepository;
import com.repopilot.task.entity.Task;
import com.repopilot.task.repository.TaskRepository;
import com.repopilot.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Phase 13 Slice 12.4 Implementation of SecurityPolicyComplianceCertificationService.
 * Durable, audit-logged, cryptographically bound compliance certificate issuance with Option A PostgreSQL 23505 race recovery.
 */
@Service
public class SecurityPolicyComplianceCertificationServiceImpl implements SecurityPolicyComplianceCertificationService {

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    private static final Logger logger = LoggerFactory.getLogger(SecurityPolicyComplianceCertificationServiceImpl.class);

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{8,128}$");

    private final ProjectAuthorizationService projectAuthorizationService;
    private final RepositoryRepository repositoryRepository;
    private final TaskRepository taskRepository;
    private final CodeReviewRepository codeReviewRepository;
    private final ReviewFindingRepository reviewFindingRepository;
    private final ComplianceCertificateRepository complianceCertificateRepository;
    private final SecurityPolicyComplianceService securityPolicyComplianceService;
    private final ReviewContextService reviewContextService;
    private final CanonicalCertificateHashService canonicalCertificateHashService;
    private final AuditLogRepository auditLogRepository;

    private final TransactionTemplate transactionTemplateWrite;
    private final TransactionTemplate transactionTemplateNewRead;

    @Autowired
    public SecurityPolicyComplianceCertificationServiceImpl(
            ProjectAuthorizationService projectAuthorizationService,
            RepositoryRepository repositoryRepository,
            TaskRepository taskRepository,
            CodeReviewRepository codeReviewRepository,
            ReviewFindingRepository reviewFindingRepository,
            ComplianceCertificateRepository complianceCertificateRepository,
            SecurityPolicyComplianceService securityPolicyComplianceService,
            ReviewContextService reviewContextService,
            CanonicalCertificateHashService canonicalCertificateHashService,
            AuditLogRepository auditLogRepository,
            PlatformTransactionManager transactionManager) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryRepository = repositoryRepository;
        this.taskRepository = taskRepository;
        this.codeReviewRepository = codeReviewRepository;
        this.reviewFindingRepository = reviewFindingRepository;
        this.complianceCertificateRepository = complianceCertificateRepository;
        this.securityPolicyComplianceService = securityPolicyComplianceService;
        this.reviewContextService = reviewContextService;
        this.canonicalCertificateHashService = canonicalCertificateHashService;
        this.auditLogRepository = auditLogRepository;

        this.transactionTemplateWrite = new TransactionTemplate(transactionManager);
        this.transactionTemplateWrite.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        this.transactionTemplateNewRead = new TransactionTemplate(transactionManager);
        this.transactionTemplateNewRead.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplateNewRead.setReadOnly(true);
    }

    @Override
    public SecurityPolicyComplianceCertificateDto issueCertificate(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            CreatePolicyComplianceCertificateRequestDto requestDto,
            User actor) {

        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        if (requestDto == null || requestDto.idempotencyKey() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request payload and idempotencyKey are required");
        }

        String idempotencyKey = requestDto.idempotencyKey();
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency key does not meet format requirements");
        }

        // 1. Initial Idempotency Read Pre-Check (Before entering write transaction)
        Optional<ComplianceCertificate> existingOpt = complianceCertificateRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOpt.isPresent()) {
            return validateAndReturnExistingCertificate(existingOpt.get(), projectId, repositoryId, taskId, reviewId, idempotencyKey);
        }

        // 2. Execute Primary Write Transaction (Option A Winner path)
        try {
            return transactionTemplateWrite.execute(status -> executeIssuanceUnderTransaction(
                    projectId, repositoryId, taskId, reviewId, requestDto, actor
            ));
        } catch (DataIntegrityViolationException dive) {
            logger.warn("PostgreSQL 23505 unique constraint race detected for idempotencyKey {}. Executing Option A Rollback + New Read Transaction recovery.", idempotencyKey);
            // 3. Option A Recovery Flow: Execute in a NEW READ TRANSACTION after PostgreSQL 23505 rollback
            return recoverExistingCertificateInNewReadTransaction(projectId, repositoryId, taskId, reviewId, idempotencyKey);
        }
    }

    @Override
    public SecurityPolicyComplianceCertificateDto getCertificate(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            UUID certificateId,
            User actor) {

        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        Project project = projectAuthorizationService.getAuthorizedProject(projectId, actor);
        projectAuthorizationService.requireMinimumRole(project, actor, ProjectRole.VIEWER);

        ComplianceCertificate cert = complianceCertificateRepository.findByIdAndProjectIdAndRepositoryIdAndTaskIdAndCodeReviewId(
                certificateId, projectId, repositoryId, taskId, reviewId
        ).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CERTIFICATE_NOT_FOUND", "Certificate not found for hierarchy"));

        SecurityPolicyComplianceCertificateDto dto = cert.toDto();
        String computedHash = canonicalCertificateHashService.hashCertificate(dto);
        if (!cert.getCertificateHash().equalsIgnoreCase(computedHash)) {
            logger.error("Persisted certificate {} hash verification failed. Stored={}, Computed={}", cert.getId(), cert.getCertificateHash(), computedHash);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CORRUPT_CERTIFICATE", "Persisted compliance certificate hash verification failed");
        }

        return dto;
    }

    @Override
    public SecurityPolicyComplianceCertificateDto getCertificateByIdempotencyKey(
            UUID projectId,
            UUID reviewId,
            String idempotencyKey,
            User actor) {

        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        Project project = projectAuthorizationService.getAuthorizedProject(projectId, actor);
        projectAuthorizationService.requireMinimumRole(project, actor, ProjectRole.VIEWER);

        ComplianceCertificate cert = complianceCertificateRepository.findByProjectIdAndCodeReviewIdAndIdempotencyKey(
                projectId, reviewId, idempotencyKey
        ).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CERTIFICATE_NOT_FOUND", "Certificate not found for idempotency key"));

        SecurityPolicyComplianceCertificateDto dto = cert.toDto();
        String computedHash = canonicalCertificateHashService.hashCertificate(dto);
        if (!cert.getCertificateHash().equalsIgnoreCase(computedHash)) {
            logger.error("Persisted certificate {} hash verification failed. Stored={}, Computed={}", cert.getId(), cert.getCertificateHash(), computedHash);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CORRUPT_CERTIFICATE", "Persisted compliance certificate hash verification failed");
        }

        return dto;
    }

    // =========================================================================
    // INTERNAL TRANSACTION EXECUTION (WRITE PATH)
    // =========================================================================

    private SecurityPolicyComplianceCertificateDto executeIssuanceUnderTransaction(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            CreatePolicyComplianceCertificateRequestDto requestDto,
            User actor) {

        // Step 1: Authorization — Minimum MEMBER role required for certificate issuance
        Project project = projectAuthorizationService.getAuthorizedProject(projectId, actor);
        projectAuthorizationService.requireMinimumRole(project, actor, ProjectRole.MEMBER);

        // Step 2: Validate Resource Hierarchy
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task not found: " + taskId));
        if (!task.getProject().getId().equals(projectId) || !task.getRepository().getId().equals(repositoryId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "HIERARCHY_MISMATCH", "Task hierarchy mismatch for project or repository");
        }

        CodeReview review = codeReviewRepository.findById(reviewId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found: " + reviewId));
        if (!review.getProject().getId().equals(projectId) ||
            !review.getRepository().getId().equals(repositoryId) ||
            !review.getTask().getId().equals(taskId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "HIERARCHY_MISMATCH", "CodeReview hierarchy mismatch for specified path parameters");
        }

        if (review.getStatus() != ReviewStatus.COMPLETED) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS",
                    "Security policy compliance certification requires a COMPLETED review status (current: " + review.getStatus() + ")");
        }

        CodeChange codeChange = review.getCodeChange();
        if (codeChange == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_CODE_CHANGE", "CodeReview is not associated with a valid CodeChange");
        }

        // Step 3: TOCTOU Re-Verification of Review Context Hash
        if (reviewContextService != null) {
            ReviewContext currentCtx = reviewContextService.buildReviewContext(
                    projectId, repositoryId, taskId, codeChange.getId(), review.getReviewMode(), actor
            );
            if (currentCtx == null || currentCtx.isStale() || !currentCtx.reviewContextHash().equalsIgnoreCase(review.getReviewContextHash())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "STALE_CONTEXT", "Review context hash does not match current CodeChange state");
            }
        }

        // Step 4: Authoritative Policy Compliance Evaluation (Slice 11 Engine)
        SecurityPolicyComplianceDto complianceResult = securityPolicyComplianceService.evaluatePolicyCompliance(
                projectId, repositoryId, taskId, reviewId, actor
        );

        // Step 5: Canonical Finding Evidence Hash Calculation (Slice 12.3 Engine)
        List<ReviewFinding> findings = reviewFindingRepository.findByReviewId(reviewId);
        List<CanonicalFindingInput> canonicalFindingInputs = new ArrayList<>();
        if (findings != null) {
            for (ReviewFinding f : findings) {
                canonicalFindingInputs.add(CanonicalFindingInput.fromEntity(f));
            }
        }
        String findingEvidenceHash = canonicalCertificateHashService.hashFindingEvidence(canonicalFindingInputs);

        // Step 6: Application Timestamp Generation (Exactly ONCE)
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID tempCertId = UUID.randomUUID();

        // Step 7: Construct Canonical Certificate Representation & Hash (Slice 12.3 Engine)
        SecurityPolicyComplianceCertificateDto tempDto = new SecurityPolicyComplianceCertificateDto(
                tempCertId,
                reviewId,
                projectId,
                repositoryId,
                taskId,
                codeChange.getId(),
                complianceResult.complianceStatus(),
                review.getPolicyVersion(),
                review.getReviewContextHash(),
                findingEvidenceHash,
                (int) complianceResult.criticalUnmitigatedCount(),
                (int) complianceResult.highUnmitigatedCount(),
                (int) complianceResult.mediumUnmitigatedCount(),
                (int) complianceResult.lowUnmitigatedCount(),
                (int) complianceResult.mitigatedFindingsCount(),
                review.getAdminOverrideReason(),
                review.getAdminOverrideByUser() != null ? review.getAdminOverrideByUser().getId() : null,
                actor.getId(),
                requestDto.idempotencyKey(),
                "temp",
                issuedAt
        );

        String certificateHash = canonicalCertificateHashService.hashCertificate(tempDto);

        // Step 8: Construct & Persist ComplianceCertificate Entity
        ComplianceCertificate cert = new ComplianceCertificate();
        cert.setId(tempCertId);
        cert.setProject(project);
        cert.setRepository(task.getRepository());
        cert.setTask(task);
        cert.setCodeReview(review);
        cert.setCodeChange(codeChange);
        cert.setComplianceStatus(complianceResult.complianceStatus());
        cert.setPolicyVersion(review.getPolicyVersion());
        cert.setReviewContextHash(review.getReviewContextHash());
        cert.setFindingEvidenceHash(findingEvidenceHash);
        cert.setCriticalUnmitigatedCount((int) complianceResult.criticalUnmitigatedCount());
        cert.setHighUnmitigatedCount((int) complianceResult.highUnmitigatedCount());
        cert.setMediumUnmitigatedCount((int) complianceResult.mediumUnmitigatedCount());
        cert.setLowUnmitigatedCount((int) complianceResult.lowUnmitigatedCount());
        cert.setMitigatedFindingsCount((int) complianceResult.mitigatedFindingsCount());
        cert.setAdminOverrideReason(review.getAdminOverrideReason());
        cert.setAdminOverrideByUser(review.getAdminOverrideByUser());
        cert.setIssuedByUser(actor);
        cert.setIdempotencyKey(requestDto.idempotencyKey());
        cert.setCertificateHash(certificateHash);
        cert.setCreatedAt(issuedAt);

        ComplianceCertificate savedCert = complianceCertificateRepository.save(cert);

        // Step 9: Atomic Audit Log Persistence in the SAME Transaction
        if (auditLogRepository != null) {
            AuditLog auditLog = new AuditLog();
            auditLog.setUser(actor);
            auditLog.setProject(project);
            auditLog.setTask(task);
            auditLog.setAction("SECURITY_POLICY_COMPLIANCE_CERTIFICATE_ISSUED");
            auditLog.setResourceType("COMPLIANCE_CERTIFICATE");
            auditLog.setResourceId(savedCert.getId());

            String metadataJson = String.format(
                    "{\"projectId\":\"%s\",\"repositoryId\":\"%s\",\"taskId\":\"%s\",\"reviewId\":\"%s\",\"codeChangeId\":\"%s\",\"complianceStatus\":\"%s\",\"idempotencyKey\":\"%s\",\"certificateHash\":\"%s\"}",
                    projectId, repositoryId, taskId, reviewId, codeChange.getId(), complianceResult.complianceStatus(),
                    requestDto.idempotencyKey(), certificateHash
            );
            auditLog.setMetadata(metadataJson);
            auditLog.setCreatedAt(issuedAt);
            auditLogRepository.save(auditLog);
        }

        logger.info("Successfully issued security policy compliance certificate {} for review {} with idempotencyKey {}",
                savedCert.getId(), reviewId, requestDto.idempotencyKey());

        return savedCert.toDto();
    }

    // =========================================================================
    // OPTION A RECOVERY FLOW (NEW READ TRANSACTION)
    // =========================================================================

    private SecurityPolicyComplianceCertificateDto recoverExistingCertificateInNewReadTransaction(
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            String idempotencyKey) {

        return transactionTemplateNewRead.execute(status -> {
            ComplianceCertificate existing = complianceCertificateRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_RACE_FAILED", "Idempotency race recovery failed: certificate not found"));

            return validateAndReturnExistingCertificate(existing, projectId, repositoryId, taskId, reviewId, idempotencyKey);
        });
    }

    private SecurityPolicyComplianceCertificateDto validateAndReturnExistingCertificate(
            ComplianceCertificate existing,
            UUID projectId,
            UUID repositoryId,
            UUID taskId,
            UUID reviewId,
            String idempotencyKey) {

        // Validate Resource Hierarchy matching (Anti-IDOR & Conflict Defense)
        if (!existing.getProject().getId().equals(projectId) ||
            !existing.getRepository().getId().equals(repositoryId) ||
            !existing.getTask().getId().equals(taskId) ||
            !existing.getCodeReview().getId().equals(reviewId) ||
            !existing.getIdempotencyKey().equals(idempotencyKey)) {
            logger.warn("Idempotency key {} conflict: requested hierarchy [p={}, r={}, t={}, rev={}] does not match existing [p={}, r={}, t={}, rev={}]",
                    idempotencyKey, projectId, repositoryId, taskId, reviewId,
                    existing.getProject().getId(), existing.getRepository().getId(), existing.getTask().getId(), existing.getCodeReview().getId());
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency key associated with conflicting resource hierarchy");
        }

        // Cryptographic Hash Verification of Persisted Historical Certificate
        SecurityPolicyComplianceCertificateDto existingDto = existing.toDto();
        String computedHash = canonicalCertificateHashService.hashCertificate(existingDto);
        if (!existing.getCertificateHash().equalsIgnoreCase(computedHash)) {
            logger.error("Persisted certificate {} hash verification failed during recovery. Stored={}, Computed={}, DTO={}",
                    existing.getId(), existing.getCertificateHash(), computedHash, existingDto);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CORRUPT_CERTIFICATE", "Persisted compliance certificate hash verification failed");
        }

        logger.info("Successfully recovered existing compliance certificate {} for idempotencyKey {}", existing.getId(), idempotencyKey);
        return existingDto;
    }
}
