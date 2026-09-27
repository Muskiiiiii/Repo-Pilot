package com.repopilot.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePolicyComplianceCertificateRequestDto(
    @NotBlank(message = "idempotencyKey is required")
    @Size(min = 8, max = 128, message = "idempotencyKey must be between 8 and 128 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{8,128}$", message = "idempotencyKey must contain only alphanumeric characters, underscores, or hyphens")
    String idempotencyKey
) {}
