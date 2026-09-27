package com.repopilot.review.dto;

import com.repopilot.review.entity.ReviewMode;

public class CodeReviewCreateRequest {

    private ReviewMode reviewMode;
    private String idempotencyKey;

    public CodeReviewCreateRequest() {
    }

    public CodeReviewCreateRequest(ReviewMode reviewMode, String idempotencyKey) {
        this.reviewMode = reviewMode;
        this.idempotencyKey = idempotencyKey;
    }

    public ReviewMode getReviewMode() {
        return reviewMode;
    }

    public void setReviewMode(ReviewMode reviewMode) {
        this.reviewMode = reviewMode;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
