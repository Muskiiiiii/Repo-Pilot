package com.repopilot.repository.entity;

import com.repopilot.project.entity.Project;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repositories")
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "github_repo_id")
    private String githubRepoId;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch = "main";

    @Column(name = "clone_url")
    private String cloneUrl;

    @Column(name = "web_url")
    private String webUrl;

    @Enumerated(EnumType.STRING)
    @Column
    private RepositoryVisibility visibility = RepositoryVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false)
    private RepositoryIndexStatus indexStatus = RepositoryIndexStatus.NOT_INDEXED;

    @Column(name = "last_indexed_at")
    private Instant lastIndexedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Repository() {
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
        this.project = project;
    }

    public String getGithubRepoId() {
        return githubRepoId;
    }

    public void setGithubRepoId(String githubRepoId) {
        this.githubRepoId = githubRepoId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getRepoName() {
        return repoName;
    }

    public void setRepoName(String repoName) {
        this.repoName = repoName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getCloneUrl() {
        return cloneUrl;
    }

    public void setCloneUrl(String cloneUrl) {
        this.cloneUrl = cloneUrl;
    }

    public String getWebUrl() {
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }

    public RepositoryVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(RepositoryVisibility visibility) {
        this.visibility = visibility;
    }

    public RepositoryIndexStatus getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(RepositoryIndexStatus indexStatus) {
        this.indexStatus = indexStatus;
    }

    public Instant getLastIndexedAt() {
        return lastIndexedAt;
    }

    public void setLastIndexedAt(Instant lastIndexedAt) {
        this.lastIndexedAt = lastIndexedAt;
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
}
