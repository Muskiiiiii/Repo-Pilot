package com.repopilot.review.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.*;
import com.repopilot.review.entity.ReviewMode;
import com.repopilot.review.service.AdminReviewOverrideService;
import com.repopilot.review.service.CodeReviewOrchestrationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}")
public class CodeReviewController {

    private final CodeReviewOrchestrationService codeReviewOrchestrationService;
    private final AdminReviewOverrideService adminReviewOverrideService;
    private final com.repopilot.review.service.CodeReviewApprovalGateService codeReviewApprovalGateService;
    private final com.repopilot.review.service.ReviewFindingService reviewFindingService;

    public CodeReviewController(
            CodeReviewOrchestrationService codeReviewOrchestrationService,
            AdminReviewOverrideService adminReviewOverrideService) {
        this(codeReviewOrchestrationService, adminReviewOverrideService, null, null);
    }

    @Autowired
    public CodeReviewController(
            CodeReviewOrchestrationService codeReviewOrchestrationService,
            AdminReviewOverrideService adminReviewOverrideService,
            @Autowired(required = false) com.repopilot.review.service.CodeReviewApprovalGateService codeReviewApprovalGateService,
            @Autowired(required = false) com.repopilot.review.service.ReviewFindingService reviewFindingService) {
        this.codeReviewOrchestrationService = codeReviewOrchestrationService;
        this.adminReviewOverrideService = adminReviewOverrideService;
        this.codeReviewApprovalGateService = codeReviewApprovalGateService;
        this.reviewFindingService = reviewFindingService;
    }

    @PostMapping("/code-changes/{codeChangeId}/reviews")
    public ResponseEntity<CodeReviewResultDto> triggerCodeChangeReview(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID codeChangeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String xIdempotencyKeyHeader,
            @RequestBody(required = false) CodeReviewCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        String idempotencyKey = resolveIdempotencyKey(idempotencyKeyHeader, xIdempotencyKeyHeader, request);
        ReviewMode reviewMode = (request != null && request.getReviewMode() != null)
                ? request.getReviewMode()
                : ReviewMode.CODE_CHANGE_REVIEW;

        CodeReviewResultDto response = codeReviewOrchestrationService.executeCodeReview(
                projectId, repositoryId, taskId, codeChangeId, reviewMode, idempotencyKey, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/reviews")
    public ResponseEntity<CodeReviewResultDto> triggerTaskReview(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String xIdempotencyKeyHeader,
            @RequestBody(required = false) CodeReviewCreateRequest request,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        String idempotencyKey = resolveIdempotencyKey(idempotencyKeyHeader, xIdempotencyKeyHeader, request);
        ReviewMode reviewMode = (request != null && request.getReviewMode() != null)
                ? request.getReviewMode()
                : ReviewMode.TASK_IMPLEMENTATION_REVIEW;

        CodeReviewResultDto response = codeReviewOrchestrationService.executeCodeReview(
                projectId, repositoryId, taskId, null, reviewMode, idempotencyKey, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/reviews/{reviewId}")
    public ResponseEntity<CodeReviewResultDto> getReviewById(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        CodeReviewResultDto response = codeReviewOrchestrationService.getCodeReviewResult(reviewId, currentUserPrincipal.getUser());

        if (!projectId.equals(response.projectId()) || !repositoryId.equals(response.repositoryId()) || !taskId.equals(response.taskId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "CodeReview not found for specified project/repository/task");
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/code-changes/{codeChangeId}/reviews")
    public ResponseEntity<List<CodeReviewResultDto>> getReviewsForCodeChange(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID codeChangeId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        List<CodeReviewResultDto> response = codeReviewOrchestrationService.getCodeReviewsForCodeChange(
                projectId, repositoryId, taskId, codeChangeId, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/reviews")
    public ResponseEntity<List<CodeReviewResultDto>> getReviewsForTask(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        List<CodeReviewResultDto> response = codeReviewOrchestrationService.getCodeReviewsForTask(
                projectId, repositoryId, taskId, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }

    @PostMapping("/reviews/{reviewId}/override")
    public ResponseEntity<AdminReviewOverrideResponseDto> overrideReview(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @Valid @RequestBody AdminReviewOverrideRequest request,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        AdminReviewOverrideResponseDto response = adminReviewOverrideService.overrideReview(
                projectId, repositoryId, taskId, reviewId, request, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/reviews/{reviewId}/findings")
    public ResponseEntity<List<ReviewFindingResponseDto>> getReviewFindings(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @RequestParam(value = "severity", required = false) String severity,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        if (reviewFindingService == null) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "ReviewFindingService is not available");
        }

        List<ReviewFindingResponseDto> findings = reviewFindingService.getFindingsForReview(
                projectId, repositoryId, taskId, reviewId, severity, currentUserPrincipal.getUser());

        return ResponseEntity.ok(findings);
    }

    @GetMapping("/code-changes/{codeChangeId}/eligibility")
    public ResponseEntity<CodeReviewEligibilityResultDto> evaluateCodeChangeReviewEligibility(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID codeChangeId,
            @RequestParam(value = "reviewId", required = false) UUID reviewId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        if (codeReviewApprovalGateService == null) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", "CodeReviewApprovalGateService is not available");
        }

        CodeReviewEligibilityResultDto result = codeReviewApprovalGateService.evaluateReviewEligibilityForApproval(
                projectId, repositoryId, taskId, codeChangeId, reviewId, currentUserPrincipal.getUser());

        return ResponseEntity.ok(result);
    }

    private String resolveIdempotencyKey(String idempotencyKeyHeader, String xIdempotencyKeyHeader, CodeReviewCreateRequest request) {
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            return idempotencyKeyHeader.trim();
        }
        if (xIdempotencyKeyHeader != null && !xIdempotencyKeyHeader.isBlank()) {
            return xIdempotencyKeyHeader.trim();
        }
        if (request != null && request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            return request.getIdempotencyKey().trim();
        }
        return null;
    }
}
