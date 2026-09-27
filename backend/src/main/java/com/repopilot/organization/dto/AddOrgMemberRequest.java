package com.repopilot.organization.dto;

import com.repopilot.organization.entity.OrganizationRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AddOrgMemberRequest {

    @NotBlank(message = "User email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotNull(message = "Organization role is required")
    private OrganizationRole role = OrganizationRole.ORG_MEMBER;

    public AddOrgMemberRequest() {
    }

    public AddOrgMemberRequest(String email, OrganizationRole role) {
        this.email = email;
        this.role = role != null ? role : OrganizationRole.ORG_MEMBER;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public OrganizationRole getRole() {
        return role;
    }

    public void setRole(OrganizationRole role) {
        this.role = role;
    }
}
