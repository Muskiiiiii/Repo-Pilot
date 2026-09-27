package com.repopilot.project.dto;

import com.repopilot.project.entity.ProjectMember;
import com.repopilot.project.entity.ProjectRole;
import com.repopilot.user.dto.UserDto;

import java.time.Instant;
import java.util.UUID;

public class ProjectMemberDto {

    private UUID id;
    private UserDto user;
    private ProjectRole role;
    private Instant createdAt;

    public ProjectMemberDto() {
    }

    public ProjectMemberDto(UUID id, UserDto user, ProjectRole role, Instant createdAt) {
        this.id = id;
        this.user = user;
        this.role = role;
        this.createdAt = createdAt;
    }

    public static ProjectMemberDto fromEntity(ProjectMember member) {
        if (member == null) {
            return null;
        }
        return new ProjectMemberDto(
                member.getId(),
                UserDto.fromEntity(member.getUser()),
                member.getRole(),
                member.getCreatedAt()
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }

    public ProjectRole getRole() {
        return role;
    }

    public void setRole(ProjectRole role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
