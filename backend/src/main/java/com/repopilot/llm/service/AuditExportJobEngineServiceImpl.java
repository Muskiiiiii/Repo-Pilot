package com.repopilot.llm.service;

import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.AuditExportJobResponse;
import com.repopilot.llm.dto.CreateAuditExportRequest;
import com.repopilot.llm.entity.AuditExportJob;
import com.repopilot.llm.entity.AuditExportJobStatus;
import com.repopilot.llm.entity.AuditLogOutbox;
import com.repopilot.llm.entity.AuditOutboxDeliveryState;
import com.repopilot.llm.repository.AuditExportJobRepository;
import com.repopilot.llm.repository.AuditLogOutboxRepository;
import com.repopilot.organization.entity.Organization;
import com.repopilot.organization.entity.OrganizationRole;
import com.repopilot.organization.repository.OrganizationRepository;
import com.repopilot.organization.service.OrganizationAuthorizationService;
import com.repopilot.user.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.repopilot.llm.crypto.AuditExportCryptoService;
import com.repopilot.llm.crypto.KeyManagementService;
import com.repopilot.llm.dto.AuditExportEncryptionResult;
import com.repopilot.llm.dto.ExportStreamingResult;
import com.repopilot.llm.dto.ObjectStorageMetadata;
import com.repopilot.llm.storage.ObjectStorageService;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AuditExportJobEngineServiceImpl implements AuditExportJobEngineService {

    private static final Set<String> ALLOWED_SCOPES = Set.of("FULL", "AUDIT_LOGS", "LLM_LIFECYCLE", "POLICY_CHANGES");
    private static final long MAX_DATE_RANGE_SECONDS = 90L * 24 * 3600;

    private final AuditExportJobRepository auditExportJobRepository;
    private final AuditLogOutboxRepository auditLogOutboxRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationAuthorizationService organizationAuthorizationService;
    private final TransactionTemplate transactionTemplate;
    private final AuditExportStreamingService auditExportStreamingService;
    private final AuditExportCryptoService auditExportCryptoService;
    private final ObjectStorageService objectStorageService;
    private final KeyManagementService keyManagementService;

    @Autowired
    public AuditExportJobEngineServiceImpl(AuditExportJobRepository auditExportJobRepository,
                                           AuditLogOutboxRepository auditLogOutboxRepository,
                                           OrganizationRepository organizationRepository,
                                           OrganizationAuthorizationService organizationAuthorizationService,
                                           TransactionTemplate transactionTemplate,
                                           AuditExportStreamingService auditExportStreamingService,
                                           AuditExportCryptoService auditExportCryptoService,
                                           ObjectStorageService objectStorageService,
                                           KeyManagementService keyManagementService) {
        this.auditExportJobRepository = auditExportJobRepository;
        this.auditLogOutboxRepository = auditLogOutboxRepository;
        this.organizationRepository = organizationRepository;
        this.organizationAuthorizationService = organizationAuthorizationService;
        this.transactionTemplate = transactionTemplate;
        this.auditExportStreamingService = auditExportStreamingService;
        this.auditExportCryptoService = auditExportCryptoService;
        this.objectStorageService = objectStorageService;
        this.keyManagementService = keyManagementService;
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AuditExportJobResponse createExportJob(UUID orgId, User caller, CreateAuditExportRequest request) {
        try {
            Organization org = organizationRepository.findByIdForUpdate(orgId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organization not found"));

            organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_ADMIN);

            validateCreateRequest(request);

            String idempotencyKey = request.getIdempotencyKey() != null ? request.getIdempotencyKey().trim() : null;
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                Optional<AuditExportJob> existingOpt = auditExportJobRepository.findByOrganizationIdAndIdempotencyKey(orgId, idempotencyKey);
                if (existingOpt.isPresent()) {
                    AuditExportJob existing = existingOpt.get();
                    validateIdempotentMatch(existing, request);
                    return mapToResponse(existing);
                }
            }

            org.setUpdatedAt(Instant.now());
            organizationRepository.saveAndFlush(org);

            long activeCount = auditExportJobRepository.countByOrganizationIdAndStatusIn(
                    orgId, List.of(AuditExportJobStatus.PENDING, AuditExportJobStatus.CLAIMED, AuditExportJobStatus.PROCESSING)
            );
            if (activeCount >= 2) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Maximum concurrent active export limit (2) reached for organization");
            }

            Instant snapshotTimestamp = Instant.now();
            Long maxSeq = auditLogOutboxRepository.findMaxSequenceNumberByOrganizationId(orgId);
            if (maxSeq == null) {
                maxSeq = 0L;
            }

            UUID latestOutboxId = null;
            Page<UUID> latestOutboxPage = auditLogOutboxRepository.findLatestOutboxIdByOrganizationId(orgId, PageRequest.of(0, 1));
            if (latestOutboxPage != null && !latestOutboxPage.getContent().isEmpty()) {
                latestOutboxId = latestOutboxPage.getContent().get(0);
            }

            AuditExportJob job = new AuditExportJob(org, caller, request.getExportScope().trim().toUpperCase(), idempotencyKey);
            job.setFilterFromTimestamp(request.getFilterFromTimestamp());
            job.setFilterToTimestamp(request.getFilterToTimestamp());
            job.setSnapshotTimestamp(snapshotTimestamp);
            job.setMaxAuditOutboxId(latestOutboxId);
            job.setMaxSequenceNumber(maxSeq);
            job.setStatus(AuditExportJobStatus.PENDING);
            job.setAttemptCount(0);
            job.setFencingVersion(1L);
            job.setExpiresAt(snapshotTimestamp.plus(Duration.ofDays(30)));

            try {
                job = auditExportJobRepository.saveAndFlush(job);
            } catch (DataIntegrityViolationException e) {
                if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                    Optional<AuditExportJob> existingOpt = auditExportJobRepository.findByOrganizationIdAndIdempotencyKey(orgId, idempotencyKey);
                    if (existingOpt.isPresent()) {
                        AuditExportJob existing = existingOpt.get();
                        validateIdempotentMatch(existing, request);
                        return mapToResponse(existing);
                    }
                }
                throw e;
            }

            recordAuditOutboxEvent(job, "AUDIT_EXPORT_REQUESTED", orgId);

            return mapToResponse(job);
        } catch (org.springframework.dao.ConcurrencyFailureException e) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Maximum concurrent active export limit (2) reached for organization");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AuditExportJobResponse getExportJob(UUID orgId, UUID jobId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(orgId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Export job not found for organization"));

        return mapToResponse(job);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditExportJobResponse> getExportJobs(UUID orgId, User caller, int page, int size) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_MEMBER);

        int cappedSize = Math.min(Math.max(size, 1), 100);
        int validPage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(validPage, cappedSize);

        Page<AuditExportJob> pageResult = auditExportJobRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId, pageable);
        return pageResult.map(this::mapToResponse);
    }

    @Override
    public Optional<AuditExportJob> claimNextJob(String workerIdentity, Duration leaseDuration) {
        UUID leaseToken = UUID.randomUUID();
        Instant now = Instant.now();
        Instant leaseExpiresAt = now.plus(leaseDuration);

        try {
            AuditExportJob claimed = transactionTemplate.execute(status -> {
                List<AuditExportJob> candidates = auditExportJobRepository.findClaimableJobs(now, PageRequest.of(0, 1));
                AuditExportJob target = (candidates == null || candidates.isEmpty()) ? null : candidates.get(0);

                if (target == null) {
                    return null;
                }

                target.setLeaseToken(leaseToken);
                target.setLeaseExpiresAt(leaseExpiresAt);
                target.setWorkerIdentity(workerIdentity);
                target.setStatus(AuditExportJobStatus.CLAIMED);
                target.setAttemptCount(target.getAttemptCount() + 1);
                if (target.getStartedAt() == null) {
                    target.setStartedAt(now);
                }
                target.setUpdatedAt(now);

                return auditExportJobRepository.saveAndFlush(target);
            });

            return Optional.ofNullable(claimed);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public boolean updateJobStatusFenced(UUID jobId, UUID leaseToken, Long fencingVersion, AuditExportJobStatus fromStatus, AuditExportJobStatus toStatus) {
        if (isTerminalState(fromStatus)) {
            return false;
        }

        AuditExportJob job = auditExportJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return false;
        }

        if (!Objects.equals(job.getLeaseToken(), leaseToken) ||
            !Objects.equals(job.getFencingVersion(), fencingVersion) ||
            job.getStatus() != fromStatus) {
            return false;
        }

        job.setStatus(toStatus);
        job.setUpdatedAt(Instant.now());
        if (toStatus == AuditExportJobStatus.COMPLETED || toStatus == AuditExportJobStatus.FAILED) {
            job.setCompletedAt(Instant.now());
        }

        auditExportJobRepository.saveAndFlush(job);
        return true;
    }

    private boolean isTerminalState(AuditExportJobStatus status) {
        return status == AuditExportJobStatus.COMPLETED ||
               status == AuditExportJobStatus.FAILED ||
               status == AuditExportJobStatus.EXPIRED ||
               status == AuditExportJobStatus.CANCELLED ||
               status == AuditExportJobStatus.DELETED;
    }

    @Override
    @Transactional
    public boolean heartbeatLease(UUID jobId, UUID leaseToken, Long fencingVersion, Duration extendDuration) {
        AuditExportJob job = auditExportJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return false;
        }

        if (!Objects.equals(job.getLeaseToken(), leaseToken) ||
            !Objects.equals(job.getFencingVersion(), fencingVersion)) {
            return false;
        }

        Instant now = Instant.now();
        job.setLeaseExpiresAt(now.plus(extendDuration));
        job.setUpdatedAt(now);
        auditExportJobRepository.saveAndFlush(job);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public com.repopilot.llm.dto.ExportStreamingResult processJobExportStream(UUID orgId, UUID jobId, UUID leaseToken, Long fencingVersion, java.io.OutputStream outputStream) {
        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(orgId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Export job not found for organization"));

        if (!Objects.equals(job.getLeaseToken(), leaseToken) || !Objects.equals(job.getFencingVersion(), fencingVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_WORKER_FENCE", "Worker lease token or fencing version mismatch");
        }

        return auditExportStreamingService.processExportStream(job, outputStream);
    }

    private void validateCreateRequest(CreateAuditExportRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Request body cannot be null");
        }
        if (request.getExportScope() == null || request.getExportScope().trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Export scope is required");
        }
        String scopeUpper = request.getExportScope().trim().toUpperCase();
        if (!ALLOWED_SCOPES.contains(scopeUpper)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Invalid export scope: " + request.getExportScope());
        }
        if (request.getIdempotencyKey() != null && request.getIdempotencyKey().length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Idempotency key exceeds maximum length of 128 characters");
        }

        Instant from = request.getFilterFromTimestamp();
        Instant to = request.getFilterToTimestamp();
        Instant now = Instant.now();

        if (from != null && from.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "filterFromTimestamp cannot be in the future");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "filterFromTimestamp cannot be after filterToTimestamp");
        }
        if (from != null && to != null) {
            long durationSec = to.getEpochSecond() - from.getEpochSecond();
            if (durationSec > MAX_DATE_RANGE_SECONDS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "Requested date range exceeds maximum allowed range of 90 days");
            }
        }
    }

    private void validateIdempotentMatch(AuditExportJob existing, CreateAuditExportRequest request) {
        String reqScope = request.getExportScope().trim().toUpperCase();
        if (!existing.getExportScope().equalsIgnoreCase(reqScope) ||
            !Objects.equals(existing.getFilterFromTimestamp(), request.getFilterFromTimestamp()) ||
            !Objects.equals(existing.getFilterToTimestamp(), request.getFilterToTimestamp())) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency key reused with different request parameters");
        }
    }

    private void recordAuditOutboxEvent(AuditExportJob job, String eventType, UUID orgId) {
        AuditLogOutbox outbox = new AuditLogOutbox();
        UUID occurrenceId = UUID.randomUUID();
        String canonicalId = "EXP:" + orgId + ":" + job.getId() + ":" + eventType + ":" + occurrenceId;
        if (canonicalId.length() > 128) {
            canonicalId = canonicalId.substring(0, 128);
        }
        outbox.setCanonicalAuditEventId(canonicalId);
        outbox.setOccurrenceId(occurrenceId);
        outbox.setOrganization(job.getOrganization());
        outbox.setAggregateType("AuditExportJob");
        outbox.setAggregateId(job.getId().toString());
        outbox.setEventType(eventType);
        outbox.setDeliveryState(AuditOutboxDeliveryState.PENDING);
        outbox.setCreatedAt(Instant.now());

        String payload = String.format("{\"organizationId\":\"%s\",\"jobId\":\"%s\",\"exportScope\":\"%s\",\"status\":\"%s\",\"eventType\":\"%s\"}",
                orgId, job.getId(), job.getExportScope(), job.getStatus(), eventType);
        outbox.setPayloadJson(payload);

        auditLogOutboxRepository.save(outbox);
    }

    @Override
    @Transactional
    public AuditExportJobResponse executeFullExportPipeline(UUID orgId, UUID jobId, String workerIdentity, UUID leaseToken, Long fencingVersion) {
        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(orgId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Export job not found for organization"));

        if (!Objects.equals(job.getLeaseToken(), leaseToken) || !Objects.equals(job.getFencingVersion(), fencingVersion)) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_WORKER_FENCE", "Worker lease token or fencing version mismatch");
        }

        if (job.getStatus() != AuditExportJobStatus.CLAIMED && job.getStatus() != AuditExportJobStatus.PROCESSING) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_JOB_STATE", "Job is not in CLAIMED or PROCESSING state");
        }

        // 1. Process GZIP export stream into byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExportStreamingResult streamingResult = auditExportStreamingService.processExportStream(job, baos);
        byte[] plainGzipBytes = baos.toByteArray();

        // 2. Encrypt using AES-256-GCM envelope encryption
        AuditExportEncryptionResult encryptionResult = auditExportCryptoService.encrypt(
                plainGzipBytes, orgId, jobId, job.getSnapshotTimestamp()
        );

        // 3. Store encrypted artifact in Object Storage
        String artifactId = "export_" + jobId.toString();
        String storageKey = objectStorageService.generateStorageKey(orgId, jobId, artifactId);
        objectStorageService.putObject(storageKey, encryptionResult.getCiphertext(), "application/octet-stream");

        // 4. Verification HEAD check on stored object
        ObjectStorageMetadata storageMetadata = objectStorageService.headObject(storageKey);
        if (storageMetadata == null || storageMetadata.getSizeBytes() != encryptionResult.getCiphertext().length) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_VERIFICATION_FAILED", "Stored artifact HEAD size verification failed");
        }

        // 5. Update Job record with encryption metadata and completed state
        job.setEncryptedDek(encryptionResult.getEncryptedDek());
        job.setGcmNonce(encryptionResult.getNonce());
        job.setKeyVersion(encryptionResult.getKeyVersion());
        job.setArtifactStoragePath(storageKey);
        job.setArtifactChecksumSha256(encryptionResult.getPlaintextHash());
        job.setEncryptedArtifactChecksumSha256(encryptionResult.getCiphertextHash());
        job.setArtifactSizeBytes((long) encryptionResult.getCiphertext().length);
        job.setRecordCount(streamingResult.getRecordCount());
        job.setStatus(AuditExportJobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setUpdatedAt(Instant.now());

        AuditExportJob saved = auditExportJobRepository.saveAndFlush(job);
        recordAuditOutboxEvent(saved, "AUDIT_EXPORT_COMPLETED", orgId);

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public AuditExportJobResponse rewrapExportKey(UUID orgId, UUID jobId, User caller) {
        organizationAuthorizationService.requireMinimumRole(orgId, caller, OrganizationRole.ORG_ADMIN);

        AuditExportJob job = auditExportJobRepository.findByOrganizationIdAndId(orgId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Export job not found for organization"));

        if (job.getStatus() != AuditExportJobStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Cannot rewrap key for job that is not COMPLETED");
        }

        if (job.getEncryptedDek() == null || job.getKeyVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JOB", "Job does not have an encrypted DEK or key version");
        }

        int currentKekVersion = keyManagementService.getCurrentKeyVersion();

        byte[] rewrappedDek = keyManagementService.rewrapDataEncryptionKey(
                job.getEncryptedDek(), job.getKeyVersion(), currentKekVersion
        );

        job.setEncryptedDek(rewrappedDek);
        job.setKeyVersion(currentKekVersion);
        job.setUpdatedAt(Instant.now());

        AuditExportJob saved = auditExportJobRepository.saveAndFlush(job);
        recordAuditOutboxEvent(saved, "AUDIT_EXPORT_KEY_REWRAPPED", orgId);

        return mapToResponse(saved);
    }

    private AuditExportJobResponse mapToResponse(AuditExportJob job) {
        AuditExportJobResponse dto = new AuditExportJobResponse();
        dto.setJobId(job.getId());
        dto.setOrganizationId(job.getOrganization().getId());
        dto.setRequestedByUserId(job.getRequestedByUser().getId());
        dto.setStatus(job.getStatus().name());
        dto.setSnapshotTimestamp(job.getSnapshotTimestamp());
        dto.setMaxSequenceNumber(job.getMaxSequenceNumber());
        dto.setMaxAuditOutboxId(job.getMaxAuditOutboxId());
        dto.setExportScope(job.getExportScope());
        dto.setFilterFromTimestamp(job.getFilterFromTimestamp());
        dto.setFilterToTimestamp(job.getFilterToTimestamp());
        dto.setFencingVersion(job.getFencingVersion());
        dto.setKeyVersion(job.getKeyVersion());
        dto.setRecordCount(job.getRecordCount());
        dto.setArtifactSizeBytes(job.getArtifactSizeBytes());
        dto.setArtifactChecksumSha256(job.getArtifactChecksumSha256());
        dto.setErrorCode(job.getErrorCode());
        dto.setErrorMessage(job.getErrorMessage());
        dto.setCreatedAt(job.getCreatedAt());
        dto.setStartedAt(job.getStartedAt());
        dto.setCompletedAt(job.getCompletedAt());
        dto.setExpiresAt(job.getExpiresAt());
        return dto;
    }
}
