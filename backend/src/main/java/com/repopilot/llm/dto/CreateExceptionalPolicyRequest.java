package com.repopilot.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateExceptionalPolicyRequest {

    @NotBlank(message = "sourceAuthorityType is required")
    @Size(max = 64, message = "sourceAuthorityType cannot exceed 64 characters")
    private String sourceAuthorityType;

    @NotBlank(message = "originalProviderId is required")
    @Size(max = 32, message = "originalProviderId cannot exceed 32 characters")
    private String originalProviderId;

    @NotBlank(message = "targetProviderId is required")
    @Size(max = 32, message = "targetProviderId cannot exceed 32 characters")
    private String targetProviderId;

    @NotBlank(message = "reason is required")
    @Size(min = 1, max = 2048, message = "reason must be between 1 and 2048 characters")
    private String reason;

    public CreateExceptionalPolicyRequest() {
    }

    public CreateExceptionalPolicyRequest(String sourceAuthorityType, String originalProviderId, String targetProviderId, String reason) {
        this.sourceAuthorityType = sourceAuthorityType;
        this.originalProviderId = originalProviderId;
        this.targetProviderId = targetProviderId;
        this.reason = reason;
    }

    public String getSourceAuthorityType() {
        return sourceAuthorityType;
    }

    public void setSourceAuthorityType(String sourceAuthorityType) {
        this.sourceAuthorityType = sourceAuthorityType;
    }

    public String getOriginalProviderId() {
        return originalProviderId;
    }

    public void setOriginalProviderId(String originalProviderId) {
        this.originalProviderId = originalProviderId;
    }

    public String getTargetProviderId() {
        return targetProviderId;
    }

    public void setTargetProviderId(String targetProviderId) {
        this.targetProviderId = targetProviderId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
