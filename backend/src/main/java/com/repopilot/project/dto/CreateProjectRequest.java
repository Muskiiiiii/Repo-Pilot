package com.repopilot.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateProjectRequest {

    @NotBlank(message = "Project name is required")
    @Size(max = 255, message = "Project name must not exceed 255 characters")
    private String name;

    private String description;
    private java.util.UUID organizationId;

    public CreateProjectRequest() {
    }

    public CreateProjectRequest(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public CreateProjectRequest(String name, String description, java.util.UUID organizationId) {
        this.name = name;
        this.description = description;
        this.organizationId = organizationId;
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

    public java.util.UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(java.util.UUID organizationId) {
        this.organizationId = organizationId;
    }
}
