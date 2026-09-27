package com.repopilot.llm.dto;

import java.util.UUID;

public class ExportStreamingResult {
    private UUID jobId;
    private UUID organizationId;
    private long recordCount;
    private long artifactSizeBytes;
    private Long maxSequenceNumber;

    public ExportStreamingResult() {
    }

    public ExportStreamingResult(UUID jobId, UUID organizationId, long recordCount, long artifactSizeBytes, Long maxSequenceNumber) {
        this.jobId = jobId;
        this.organizationId = organizationId;
        this.recordCount = recordCount;
        this.artifactSizeBytes = artifactSizeBytes;
        this.maxSequenceNumber = maxSequenceNumber;
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

    public long getRecordCount() {
        return recordCount;
    }

    public void setRecordCount(long recordCount) {
        this.recordCount = recordCount;
    }

    public long getArtifactSizeBytes() {
        return artifactSizeBytes;
    }

    public void setArtifactSizeBytes(long artifactSizeBytes) {
        this.artifactSizeBytes = artifactSizeBytes;
    }

    public Long getMaxSequenceNumber() {
        return maxSequenceNumber;
    }

    public void setMaxSequenceNumber(Long maxSequenceNumber) {
        this.maxSequenceNumber = maxSequenceNumber;
    }
}
