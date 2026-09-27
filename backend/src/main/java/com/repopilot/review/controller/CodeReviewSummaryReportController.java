package com.repopilot.review.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.CodeReviewSummaryReportDto;
import com.repopilot.review.service.CodeReviewSummaryReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks/{taskId}")
public class CodeReviewSummaryReportController {

    private final CodeReviewSummaryReportService summaryReportService;

    @Autowired
    public CodeReviewSummaryReportController(CodeReviewSummaryReportService summaryReportService) {
        this.summaryReportService = summaryReportService;
    }

    @GetMapping("/review-summary")
    public ResponseEntity<CodeReviewSummaryReportDto> getReviewSummary(
            @PathVariable UUID projectId,
            @PathVariable UUID taskId,
            @RequestParam(name = "codeChangeId", required = false) UUID codeChangeId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User must be authenticated to request review summary");
        }

        CodeReviewSummaryReportDto report = summaryReportService.generateReviewSummary(
                projectId,
                taskId,
                codeChangeId,
                currentUserPrincipal.getUser()
        );

        return ResponseEntity.ok(report);
    }
}
