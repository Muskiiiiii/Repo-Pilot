package com.repopilot.review.dto;

import com.repopilot.review.entity.FindingStatus;

public class UpdateFindingDispositionRequestDto {

    private FindingStatus status;
    private String reason;

    public UpdateFindingDispositionRequestDto() {
    }

    public UpdateFindingDispositionRequestDto(FindingStatus status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public FindingStatus getStatus() {
        return status;
    }

    public void setStatus(FindingStatus status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
