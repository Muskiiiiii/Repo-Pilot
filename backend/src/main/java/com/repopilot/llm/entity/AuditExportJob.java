package com.repopilot.llm.entity;

import com.repopilot.organization.entity.Organization;
import com.repopilot.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_export_jobs", uniqueConstraints = {
    @UniqueConstraint(name = "uk_audit_export_job_org_idempotency", columnNames = {"organization_id", "idempotency_key"})
})
public class AuditExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_user_id", nullable = false)
    private User requestedByUser;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AuditExportJobStatus status = AuditExportJobStatus.PENDING;

    @Column(name = "snapshot_timestamp", nullable = false)
    private Instant snapshotTimestamp = Instant.now();

    @Column(name = "max_sequence_number", nullable = false)
    private Long maxSequenceNumber = 0L;

    @Column(name = "max_audit_outbox_id")
    private UUID maxAuditOutboxId;

    @Column(name = "export_scope", nullable = false, length = 64)
    private String exportScope;

    @Column(name = "filter_from_timestamp")
    private Instant filterFromTimestamp;

    @Column(name = "filter_to_timestamp")
    private Instant filterToTimestamp;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "worker_identity", length = 128)
    private String workerIdentity;

    @Version
    @Column(name = "fencing_version", nullable = false)
    private Long fencingVersion = 1L;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "key_version", nullable = false)
    private Integer keyVersion = 1;

    @Column(name = "encrypted_dek")
    private byte[] encryptedDek;

    @Column(name = "gcm_nonce")
    private byte[] gcmNonce;

    @Column(name = "artifact_storage_path", length = 512)
    private String artifactStoragePath;

    @Column(name = "artifact_checksum_sha256", length = 64)
    private String artifactChecksumSha256;

    @Column(name = "encrypted_artifact_checksum_sha256", length = 64)
    private String encryptedArtifactChecksumSha256;

    @Column(name = "artifact_size_bytes")
    private Long artifactSizeBytes;

    @Column(name = "record_count")
    private Long recordCount;

    @Column(name = "legal_hold", nullable = false)
    private Boolean legalHold = false;

    @Column(name = "artifact_purged", nullable = false)
    private Boolean artifactPurged = false;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public AuditExportJob() {
    }

    public AuditExportJob(Organization organization, User requestedByUser, String exportScope, String idempotencyKey) {
        this.organization = organization;
        this.requestedByUser = requestedByUser;
        this.exportScope = exportScope;
        this.idempotencyKey = idempotencyKey;
        this.status = AuditExportJobStatus.PENDING;
        this.snapshotTimestamp = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Organization getOrganization() {
        return organization;
    }

    public void setOrganization(Organization organization) {
        this.organization = organization;
    }

    public User getRequestedByUser() {
        return requestedByUser;
    }

    public void setRequestedByUser(User requestedByUser) {
        this.requestedByUser = requestedByUser;
    }

    public AuditExportJobStatus getStatus() {
        return status;
    }

    public void setStatus(AuditExportJobStatus status) {
        this.status = status;
    }

    public Instant getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    public void setSnapshotTimestamp(Instant snapshotTimestamp) {
        this.snapshotTimestamp = snapshotTimestamp;
    }

    public Long getMaxSequenceNumber() {
        return maxSequenceNumber;
    }

    public void setMaxSequenceNumber(Long maxSequenceNumber) {
        this.maxSequenceNumber = maxSequenceNumber;
    }

    public UUID getMaxAuditOutboxId() {
        return maxAuditOutboxId;
    }

    public void setMaxAuditOutboxId(UUID maxAuditOutboxId) {
        this.maxAuditOutboxId = maxAuditOutboxId;
    }

    public String getExportScope() {
        return exportScope;
    }

    public void setExportScope(String exportScope) {
        this.exportScope = exportScope;
    }

    public Instant getFilterFromTimestamp() {
        return filterFromTimestamp;
    }

    public void setFilterFromTimestamp(Instant filterFromTimestamp) {
        this.filterFromTimestamp = filterFromTimestamp;
    }

    public Instant getFilterToTimestamp() {
        return filterToTimestamp;
    }

    public void setFilterToTimestamp(Instant filterToTimestamp) {
        this.filterToTimestamp = filterToTimestamp;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(UUID leaseToken) {
        this.leaseToken = leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public String getWorkerIdentity() {
        return workerIdentity;
    }

    public void setWorkerIdentity(String workerIdentity) {
        this.workerIdentity = workerIdentity;
    }

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(Integer keyVersion) {
        this.keyVersion = keyVersion;
    }

    public byte[] getEncryptedDek() {
        return encryptedDek;
    }

    public void setEncryptedDek(byte[] encryptedDek) {
        this.encryptedDek = encryptedDek;
    }

    public byte[] getGcmNonce() {
        return gcmNonce;
    }

    public void setGcmNonce(byte[] gcmNonce) {
        this.gcmNonce = gcmNonce;
    }

    public String getArtifactStoragePath() {
        return artifactStoragePath;
    }

    public void setArtifactStoragePath(String artifactStoragePath) {
        this.artifactStoragePath = artifactStoragePath;
    }

    public String getArtifactChecksumSha256() {
        return artifactChecksumSha256;
    }

    public void setArtifactChecksumSha256(String artifactChecksumSha256) {
        this.artifactChecksumSha256 = artifactChecksumSha256;
    }

    public String getEncryptedArtifactChecksumSha256() {
        return encryptedArtifactChecksumSha256;
    }

    public void setEncryptedArtifactChecksumSha256(String encryptedArtifactChecksumSha256) {
        this.encryptedArtifactChecksumSha256 = encryptedArtifactChecksumSha256;
    }

    public Long getArtifactSizeBytes() {
        return artifactSizeBytes;
    }

    public void setArtifactSizeBytes(Long artifactSizeBytes) {
        this.artifactSizeBytes = artifactSizeBytes;
    }

    public Long getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(Long recordCount) {
        this.recordCount = recordCount;
    }

    public Boolean getLegalHold() {
        return legalHold;
    }

    public void setLegalHold(Boolean legalHold) {
        this.legalHold = legalHold;
    }

    public Boolean getArtifactPurged() {
        return artifactPurged;
    }

    public void setArtifactPurged(Boolean artifactPurged) {
        this.artifactPurged = artifactPurged;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
