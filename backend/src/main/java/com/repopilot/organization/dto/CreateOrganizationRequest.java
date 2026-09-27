package com.repopilot.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateOrganizationRequest {

    @NotBlank(message = "Organization name is required")
    @Size(min = 1, max = 255, message = "Organization name must be between 1 and 255 characters")
    private String name;

    @NotBlank(message = "Organization slug is required")
    @Size(min = 1, max = 255, message = "Organization slug must be between 1 and 255 characters")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug must contain only lowercase letters, numbers, and hyphens")
    private String slug;

    public CreateOrganizationRequest() {
    }

    public CreateOrganizationRequest(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }
}
