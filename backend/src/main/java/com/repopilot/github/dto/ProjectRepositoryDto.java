package com.repopilot.github.dto;

import com.repopilot.repository.entity.Repository;

import java.time.Instant;
import java.util.UUID;

public class ProjectRepositoryDto {

    private UUID id;
    private UUID projectId;
    private String githubRepoId;
    private String ownerName;
    private String repoName;
    private String defaultBranch;
    private String cloneUrl;
    private String webUrl;
    private String visibility;
    private String indexStatus;
    private Instant lastIndexedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public ProjectRepositoryDto() {
    }

    public static ProjectRepositoryDto fromEntity(Repository repo) {
        ProjectRepositoryDto dto = new ProjectRepositoryDto();
        dto.setId(repo.getId());
        if (repo.getProject() != null) {
            dto.setProjectId(repo.getProject().getId());
        }
        dto.setGithubRepoId(repo.getGithubRepoId());
        dto.setOwnerName(repo.getOwnerName());
        dto.setRepoName(repo.getRepoName());
        dto.setDefaultBranch(repo.getDefaultBranch());
        dto.setCloneUrl(repo.getCloneUrl());
        dto.setWebUrl(repo.getWebUrl());
        if (repo.getVisibility() != null) {
            dto.setVisibility(repo.getVisibility().name());
        }
        if (repo.getIndexStatus() != null) {
            dto.setIndexStatus(repo.getIndexStatus().name());
        }
        dto.setLastIndexedAt(repo.getLastIndexedAt());
        dto.setCreatedAt(repo.getCreatedAt());
        dto.setUpdatedAt(repo.getUpdatedAt());
        return dto;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
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

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(String indexStatus) {
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
