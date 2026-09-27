package com.repopilot.task.dto;

import java.time.Instant;
import java.util.UUID;

public class TaskResponseDto {

    private UUID id;
    private UUID projectId;
    private UUID repositoryId;
    private String title;
    private String description;
    private String status;
    private String priority;
    private String issueUnderstanding;
    private Instant createdAt;
    private Instant updatedAt;

    public TaskResponseDto() {
    }

    public TaskResponseDto(UUID id, UUID projectId, UUID repositoryId, String title, String description,
                           String status, String priority, String issueUnderstanding,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.repositoryId = repositoryId;
        this.title = title;
        this.description = description;
        this.status = status;
        this.priority = priority;
        this.issueUnderstanding = issueUnderstanding;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public String getIssueUnderstanding() { return issueUnderstanding; }
    public void setIssueUnderstanding(String issueUnderstanding) { this.issueUnderstanding = issueUnderstanding; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
