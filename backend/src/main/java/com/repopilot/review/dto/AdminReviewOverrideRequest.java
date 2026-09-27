package com.repopilot.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminReviewOverrideRequest {

    @NotBlank(message = "Admin override reason cannot be blank")
    @Size(max = 2000, message = "Admin override reason cannot exceed 2000 characters")
    private String reason;

    public AdminReviewOverrideRequest() {
    }

    public AdminReviewOverrideRequest(String reason) {
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
