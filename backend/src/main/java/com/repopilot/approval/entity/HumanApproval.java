package com.repopilot.approval.entity;

import com.repopilot.change.entity.CodeChange;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.review.entity.CodeReview;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "approval_records", uniqueConstraints = {
    @UniqueConstraint(name = "uk_approval_change_version", columnNames = {"code_change_id", "approval_version"}),
    @UniqueConstraint(name = "uk_approval_idempotency_key", columnNames = {"idempotency_key"})
})
public class HumanApproval {

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "code_change_id", nullable = false)
    private CodeChange codeChange;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private CodeReview review;

    @Column(name = "approval_version", nullable = false)
    private Integer approvalVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApprovalStatus status = ApprovalStatus.PENDING_APPROVAL;

    @Column(name = "repository_base_sha", nullable = false, length = 40)
    private String repositoryBaseSha;

    @Column(name = "code_change_original_hash", nullable = false, length = 64)
    private String codeChangeOriginalHash;

    @Column(name = "code_change_proposed_hash", nullable = false, length = 64)
    private String codeChangeProposedHash;

    @Column(name = "code_change_diff_hash", nullable = false, length = 64)
    private String codeChangeDiffHash;

    @Column(name = "review_context_hash", nullable = false, length = 64)
    private String reviewContextHash;

    @Column(name = "approved_files", nullable = false, columnDefinition = "TEXT")
    private String approvedFiles;

    @Column(name = "approved_change_count", nullable = false)
    private Integer approvedChangeCount = 1;

    @Column(name = "approval_record_hash", nullable = false, length = 64)
    private String approvalRecordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approved_by_user_id", nullable = false)
    private User approvedByUser;

    @Column(name = "approved_by_user_email", nullable = false)
    private String approvedByUserEmail;

    @Column(name = "approved_by_user_role", nullable = false, length = 50)
    private String approvedByUserRole;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt = Instant.now();

    @Column(name = "stale_reason")
    private String staleReason;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Version
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public HumanApproval() {
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
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
        checkImmutableField("project");
        this.project = project;
    }

    public Repository getRepository() {
        return repository;
    }

    public void setRepository(Repository repository) {
        checkImmutableField("repository");
        this.repository = repository;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        checkImmutableField("task");
        this.task = task;
    }

    public CodeChange getCodeChange() {
        return codeChange;
    }

    public void setCodeChange(CodeChange codeChange) {
        checkImmutableField("codeChange");
        this.codeChange = codeChange;
    }

    public CodeReview getReview() {
        return review;
    }

    public void setReview(CodeReview review) {
        checkImmutableField("review");
        this.review = review;
    }

    public Integer getApprovalVersion() {
        return approvalVersion;
    }

    public void setApprovalVersion(Integer approvalVersion) {
        checkImmutableField("approvalVersion");
        this.approvalVersion = approvalVersion;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public void setStatus(ApprovalStatus status) {
        this.status = status;
    }

    public String getRepositoryBaseSha() {
        return repositoryBaseSha;
    }

    public void setRepositoryBaseSha(String repositoryBaseSha) {
        checkImmutableField("repositoryBaseSha");
        this.repositoryBaseSha = repositoryBaseSha;
    }

    public String getCodeChangeOriginalHash() {
        return codeChangeOriginalHash;
    }

    public void setCodeChangeOriginalHash(String codeChangeOriginalHash) {
        checkImmutableField("codeChangeOriginalHash");
        this.codeChangeOriginalHash = codeChangeOriginalHash;
    }

    public String getCodeChangeProposedHash() {
        return codeChangeProposedHash;
    }

    public void setCodeChangeProposedHash(String codeChangeProposedHash) {
        checkImmutableField("codeChangeProposedHash");
        this.codeChangeProposedHash = codeChangeProposedHash;
    }

    public String getCodeChangeDiffHash() {
        return codeChangeDiffHash;
    }

    public void setCodeChangeDiffHash(String codeChangeDiffHash) {
        checkImmutableField("codeChangeDiffHash");
        this.codeChangeDiffHash = codeChangeDiffHash;
    }

    public String getReviewContextHash() {
        return reviewContextHash;
    }

    public void setReviewContextHash(String reviewContextHash) {
        checkImmutableField("reviewContextHash");
        this.reviewContextHash = reviewContextHash;
    }

    public String getApprovedFiles() {
        return approvedFiles;
    }

    public void setApprovedFiles(String approvedFiles) {
        checkImmutableField("approvedFiles");
        this.approvedFiles = approvedFiles;
    }

    public Integer getApprovedChangeCount() {
        return approvedChangeCount;
    }

    public void setApprovedChangeCount(Integer approvedChangeCount) {
        checkImmutableField("approvedChangeCount");
        this.approvedChangeCount = approvedChangeCount;
    }

    public String getApprovalRecordHash() {
        return approvalRecordHash;
    }

    public void setApprovalRecordHash(String approvalRecordHash) {
        checkImmutableField("approvalRecordHash");
        this.approvalRecordHash = approvalRecordHash;
    }

    public User getApprovedByUser() {
        return approvedByUser;
    }

    public void setApprovedByUser(User approvedByUser) {
        checkImmutableField("approvedByUser");
        this.approvedByUser = approvedByUser;
    }

    public String getApprovedByUserEmail() {
        return approvedByUserEmail;
    }

    public void setApprovedByUserEmail(String approvedByUserEmail) {
        checkImmutableField("approvedByUserEmail");
        this.approvedByUserEmail = approvedByUserEmail;
    }

    public String getApprovedByUserRole() {
        return approvedByUserRole;
    }

    public void setApprovedByUserRole(String approvedByUserRole) {
        checkImmutableField("approvedByUserRole");
        this.approvedByUserRole = approvedByUserRole;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        checkImmutableField("approvedAt");
        this.approvedAt = approvedAt;
    }

    public String getStaleReason() {
        return staleReason;
    }

    public void setStaleReason(String staleReason) {
        this.staleReason = staleReason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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

    private void checkImmutableField(String fieldName) {
        if (this.id != null && this.status != null && this.status.isApproved()) {
            throw new IllegalStateException("Cannot modify immutable field '" + fieldName + "' on an approved HumanApproval record (id=" + this.id + ")");
        }
    }
}
