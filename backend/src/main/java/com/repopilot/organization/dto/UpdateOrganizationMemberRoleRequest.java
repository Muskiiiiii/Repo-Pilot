package com.repopilot.organization.dto;

import com.repopilot.organization.entity.OrganizationRole;
import jakarta.validation.constraints.NotNull;

public class UpdateOrganizationMemberRoleRequest {

    @NotNull(message = "Organization role is required")
    private OrganizationRole role;

    private Long version;

    public UpdateOrganizationMemberRoleRequest() {
    }

    public UpdateOrganizationMemberRoleRequest(OrganizationRole role) {
        this.role = role;
    }

    public UpdateOrganizationMemberRoleRequest(OrganizationRole role, Long version) {
        this.role = role;
        this.version = version;
    }

    public OrganizationRole getRole() {
        return role;
    }

    public void setRole(OrganizationRole role) {
        this.role = role;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
