package com.repopilot.project.dto;

import com.repopilot.project.entity.Project;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.project.entity.ProjectStatus;
import com.repopilot.user.dto.UserDto;

import java.time.Instant;
import java.util.UUID;

public class ProjectDto {

    private UUID id;
    private String name;
    private String description;
    private UserDto owner;
    private ProjectStatus status;
    private ProjectRole userRole;
    private Instant createdAt;
    private Instant updatedAt;
    private long memberCount;
    private long repositoryCount;
    private long taskCount;

    public ProjectDto() {
    }

    public ProjectDto(UUID id, String name, String description, UserDto owner, ProjectStatus status,
                      ProjectRole userRole, Instant createdAt, Instant updatedAt,
                      long memberCount, long repositoryCount, long taskCount) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.status = status;
        this.userRole = userRole;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.memberCount = memberCount;
        this.repositoryCount = repositoryCount;
        this.taskCount = taskCount;
    }

    public static ProjectDto fromEntity(Project project, ProjectRole userRole, long memberCount, long repositoryCount, long taskCount) {
        if (project == null) {
            return null;
        }
        return new ProjectDto(
                project.getId(),
                project.getName(),
                project.getDescription(),
                UserDto.fromEntity(project.getOwner()),
                project.getStatus(),
                userRole,
                project.getCreatedAt(),
                project.getUpdatedAt(),
                memberCount,
                repositoryCount,
                taskCount
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UserDto getOwner() {
        return owner;
    }

    public void setOwner(UserDto owner) {
        this.owner = owner;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
    }

    public ProjectRole getUserRole() {
        return userRole;
    }

    public void setUserRole(ProjectRole userRole) {
        this.userRole = userRole;
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

    public long getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(long memberCount) {
        this.memberCount = memberCount;
    }

    public long getRepositoryCount() {
        return repositoryCount;
    }

    public void setRepositoryCount(long repositoryCount) {
        this.repositoryCount = repositoryCount;
    }

    public long getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(long taskCount) {
        this.taskCount = taskCount;
    }
}
