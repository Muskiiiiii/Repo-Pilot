package com.repopilot.review.entity;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "code_reviews", uniqueConstraints = {
    @UniqueConstraint(name = "uk_code_reviews_change_context_mode_ai_hash", columnNames = {"code_change_id", "review_context_hash", "review_mode", "ai_input_hash"})
})
public class CodeReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "code_change_id")
    private CodeChange codeChange;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_mode", nullable = false, length = 50)
    private ReviewMode reviewMode = ReviewMode.CODE_CHANGE_REVIEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReviewStatus status = ReviewStatus.IN_PROGRESS;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "overall_risk_score", nullable = false, length = 20)
    private String overallRiskScore = "LOW";

    @Column(name = "review_context_hash", nullable = false, length = 64)
    private String reviewContextHash;

    @Column(name = "ai_input_hash", length = 64)
    private String aiInputHash;

    @Column(name = "model_identifier", nullable = false, length = 100)
    private String modelIdentifier;

    @Column(name = "policy_version", nullable = false, length = 50)
    private String policyVersion = "v1.0.0";

    @Column(name = "reviewer_version", nullable = false, length = 50)
    private String reviewerVersion = "1.0.0";

    @Column(name = "prompt_version", nullable = false, length = 50)
    private String promptVersion = "p2026.1";

    @Column(name = "validator_version", nullable = false, length = 50)
    private String validatorVersion = "1.0.0";

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "execution_attempt_count", nullable = false)
    private Integer executionAttemptCount = 0;

    @Column(name = "admin_override_reason", columnDefinition = "TEXT")
    private String adminOverrideReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_override_by_user_id")
    private User adminOverrideByUser;

    @Column(name = "admin_override_at")
    private Instant adminOverrideAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public CodeReview() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public CodeChange getCodeChange() {
        return codeChange;
    }

    public void setCodeChange(CodeChange codeChange) {
        this.codeChange = codeChange;
    }

    public ReviewMode getReviewMode() {
        return reviewMode;
    }

    public void setReviewMode(ReviewMode reviewMode) {
        this.reviewMode = reviewMode;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewStatus status) {
        this.status = status;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getOverallRiskScore() {
        return overallRiskScore;
    }

    public void setOverallRiskScore(String overallRiskScore) {
        this.overallRiskScore = overallRiskScore;
    }

    public String getReviewContextHash() {
        return reviewContextHash;
    }

    public void setReviewContextHash(String reviewContextHash) {
        this.reviewContextHash = reviewContextHash;
    }

    public String getAiInputHash() {
        return aiInputHash;
    }

    public void setAiInputHash(String aiInputHash) {
        this.aiInputHash = aiInputHash;
    }

    public String getModelIdentifier() {
        return modelIdentifier;
    }

    public void setModelIdentifier(String modelIdentifier) {
        this.modelIdentifier = modelIdentifier;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getReviewerVersion() {
        return reviewerVersion;
    }

    public void setReviewerVersion(String reviewerVersion) {
        this.reviewerVersion = reviewerVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getValidatorVersion() {
        return validatorVersion;
    }

    public void setValidatorVersion(String validatorVersion) {
        this.validatorVersion = validatorVersion;
    }

    public Long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(Long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public void setLeaseToken(String leaseToken) {
        this.leaseToken = leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Integer getExecutionAttemptCount() {
        return executionAttemptCount;
    }

    public void setExecutionAttemptCount(Integer executionAttemptCount) {
        this.executionAttemptCount = executionAttemptCount != null ? executionAttemptCount : 0;
    }

    public String getAdminOverrideReason() {
        return adminOverrideReason;
    }

    public void setAdminOverrideReason(String adminOverrideReason) {
        this.adminOverrideReason = adminOverrideReason;
    }

    public User getAdminOverrideByUser() {
        return adminOverrideByUser;
    }

    public void setAdminOverrideByUser(User adminOverrideByUser) {
        this.adminOverrideByUser = adminOverrideByUser;
    }

    public Instant getAdminOverrideAt() {
        return adminOverrideAt;
    }

    public void setAdminOverrideAt(Instant adminOverrideAt) {
        this.adminOverrideAt = adminOverrideAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
