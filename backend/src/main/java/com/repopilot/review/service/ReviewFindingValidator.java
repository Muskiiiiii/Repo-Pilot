package com.repopilot.review.service;

import com.repopilot.review.dto.ReviewContext;
import com.repopilot.review.dto.ReviewResponseDto;
import com.repopilot.review.dto.ValidationResult;

public interface ReviewFindingValidator {

    ValidationResult validateFindings(ReviewContext context, ReviewResponseDto reviewResponse);
}
