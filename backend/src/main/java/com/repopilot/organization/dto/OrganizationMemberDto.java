package com.repopilot.organization.dto;

import com.repopilot.organization.entity.OrganizationMember;
import com.repopilot.organization.entity.OrganizationRole;

import java.time.Instant;
import java.util.UUID;

public class OrganizationMemberDto {

    private UUID id;
    private UUID organizationId;
    private UUID userId;
    private String userEmail;
    private String userName;
    private OrganizationRole role;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public OrganizationMemberDto() {
    }

    public OrganizationMemberDto(UUID id, UUID organizationId, UUID userId, String userEmail, String userName,
                                 OrganizationRole role, Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.organizationId = organizationId;
        this.userId = userId;
        this.userEmail = userEmail;
        this.userName = userName;
        this.role = role;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static OrganizationMemberDto fromEntity(OrganizationMember member) {
        if (member == null) return null;
        return new OrganizationMemberDto(
                member.getId(),
                member.getOrganization() != null ? member.getOrganization().getId() : null,
                member.getUser() != null ? member.getUser().getId() : null,
                member.getUser() != null ? member.getUser().getEmail() : null,
                member.getUser() != null ? member.getUser().getName() : null,
                member.getRole(),
                member.getCreatedAt(),
                member.getUpdatedAt(),
                member.getVersion()
        );
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(UUID organizationId) {
        this.organizationId = organizationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public OrganizationRole getRole() {
        return role;
    }

    public void setRole(OrganizationRole role) {
        this.role = role;
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

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
