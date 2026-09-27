package com.repopilot.testing.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sandbox_execution_artifacts")
public class SandboxExecutionArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_execution_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SandboxJobExecution jobExecution;

    @Column(name = "artifact_type", nullable = false, length = 50)
    private String artifactType;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "raw_output", columnDefinition = "TEXT")
    private String rawOutput;

    @Column(name = "sanitized_output", nullable = false, columnDefinition = "TEXT")
    private String sanitizedOutput;

    @Column(name = "secret_scrubbed_count", nullable = false)
    private Integer secretScrubbedCount = 0;

    @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public SandboxExecutionArtifact() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public SandboxJobExecution getJobExecution() {
        return jobExecution;
    }

    public void setJobExecution(SandboxJobExecution jobExecution) {
        this.jobExecution = jobExecution;
    }

    public String getArtifactType() {
        return artifactType;
    }

    public void setArtifactType(String artifactType) {
        this.artifactType = artifactType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public void setRawOutput(String rawOutput) {
        this.rawOutput = rawOutput;
    }

    public String getSanitizedOutput() {
        return sanitizedOutput;
    }

    public void setSanitizedOutput(String sanitizedOutput) {
        this.sanitizedOutput = sanitizedOutput;
    }

    public Integer getSecretScrubbedCount() {
        return secretScrubbedCount;
    }

    public void setSecretScrubbedCount(Integer secretScrubbedCount) {
        this.secretScrubbedCount = secretScrubbedCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
