package com.repopilot.review.service;

import com.repopilot.review.dto.CodeReviewSummaryReportDto;
import com.repopilot.user.entity.User;

import java.util.UUID;

public interface CodeReviewSummaryReportService {

    CodeReviewSummaryReportDto generateReviewSummary(
            UUID projectId,
            UUID taskId,
            UUID codeChangeId,
            User requester
    );
}
