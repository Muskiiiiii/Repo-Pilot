package com.repopilot.llm.dto;

import java.time.Instant;
import java.util.UUID;

public class AuditExportJobResponse {

    private UUID jobId;
    private UUID organizationId;
    private UUID requestedByUserId;
    private String status;
    private Instant snapshotTimestamp;
    private Long maxSequenceNumber;
    private UUID maxAuditOutboxId;
    private String exportScope;
    private Instant filterFromTimestamp;
    private Instant filterToTimestamp;
    private Long fencingVersion;
    private Integer keyVersion;
    private Long recordCount;
    private Long artifactSizeBytes;
    private String artifactChecksumSha256;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant expiresAt;

    public AuditExportJobResponse() {
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getRequestedByUserId() {
        return requestedByUserId;
    }

    public void setRequestedByUserId(UUID requestedByUserId) {
        this.requestedByUserId = requestedByUserId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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

    public Long getFencingVersion() {
        return fencingVersion;
    }

    public void setFencingVersion(Long fencingVersion) {
        this.fencingVersion = fencingVersion;
    }

    public Integer getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(Integer keyVersion) {
        this.keyVersion = keyVersion;
    }

    public Long getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(Long recordCount) {
        this.recordCount = recordCount;
    }

    public Long getArtifactSizeBytes() {
        return artifactSizeBytes;
    }

    public void setArtifactSizeBytes(Long artifactSizeBytes) {
        this.artifactSizeBytes = artifactSizeBytes;
    }

    public String getArtifactChecksumSha256() {
        return artifactChecksumSha256;
    }

    public void setArtifactChecksumSha256(String artifactChecksumSha256) {
        this.artifactChecksumSha256 = artifactChecksumSha256;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
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
