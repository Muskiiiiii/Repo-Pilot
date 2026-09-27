package com.repopilot.approval.entity;

import com.repopilot.approval.dto.PullRequestPublicationStatus;
import com.repopilot.change.entity.CodeChange;
import com.repopilot.project.entity.Project;
import com.repopilot.repository.entity.Repository;
import com.repopilot.task.entity.Task;
import com.repopilot.user.entity.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pull_request_publications", uniqueConstraints = {
        @UniqueConstraint(name = "uk_pr_pub_approval", columnNames = {"approval_id"}),
        @UniqueConstraint(name = "uk_pr_pub_repo_branch", columnNames = {"repository_id", "branch_name"})
})
public class PullRequestPublication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_id", nullable = false)
    private HumanApproval approval;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "code_change_id", nullable = false)
    private CodeChange codeChange;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by_user_id", nullable = false)
    private User initiatedByUser;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(name = "base_branch", nullable = false)
    private String baseBranch;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "parent_commit_sha", nullable = false)
    private String parentCommitSha;

    @Column(name = "tree_sha", nullable = false)
    private String treeSha;

    @Column(name = "github_repo_id", nullable = false)
    private String githubRepoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", nullable = false)
    private PullRequestPublicationStatus publicationStatus = PullRequestPublicationStatus.PENDING;

    @Column(name = "failure_category")
    private String failureCategory;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "github_pr_number")
    private Long githubPrNumber;

    @Column(name = "pr_url", columnDefinition = "TEXT")
    private String prUrl;

    @Version
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PullRequestPublication() {
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

    public HumanApproval getApproval() {
        return approval;
    }

    public void setApproval(HumanApproval approval) {
        this.approval = approval;
    }

    public CodeChange getCodeChange() {
        return codeChange;
    }

    public void setCodeChange(CodeChange codeChange) {
        this.codeChange = codeChange;
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

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public User getInitiatedByUser() {
        return initiatedByUser;
    }

    public void setInitiatedByUser(User initiatedByUser) {
        this.initiatedByUser = initiatedByUser;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
    }

    public String getBaseBranch() {
        return baseBranch;
    }

    public void setBaseBranch(String baseBranch) {
        this.baseBranch = baseBranch;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public String getParentCommitSha() {
        return parentCommitSha;
    }

    public void setParentCommitSha(String parentCommitSha) {
        this.parentCommitSha = parentCommitSha;
    }

    public String getTreeSha() {
        return treeSha;
    }

    public void setTreeSha(String treeSha) {
        this.treeSha = treeSha;
    }

    public String getGithubRepoId() {
        return githubRepoId;
    }

    public void setGithubRepoId(String githubRepoId) {
        this.githubRepoId = githubRepoId;
    }

    public PullRequestPublicationStatus getPublicationStatus() {
        return publicationStatus;
    }

    public void setPublicationStatus(PullRequestPublicationStatus publicationStatus) {
        this.publicationStatus = publicationStatus;
    }

    public String getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(String failureCategory) {
        this.failureCategory = failureCategory;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Long getGithubPrNumber() {
        return githubPrNumber;
    }

    public void setGithubPrNumber(Long githubPrNumber) {
        this.githubPrNumber = githubPrNumber;
    }

    public String getPrUrl() {
        return prUrl;
    }

    public void setPrUrl(String prUrl) {
        this.prUrl = prUrl;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
