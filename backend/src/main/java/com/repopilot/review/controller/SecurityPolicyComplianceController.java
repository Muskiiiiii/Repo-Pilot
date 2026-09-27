package com.repopilot.review.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.review.dto.SecurityPolicyComplianceDto;
import com.repopilot.review.service.SecurityPolicyComplianceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for Phase 13 Slice 11 Automated Security Policy & Compliance Evaluation Engine.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories/{repositoryId}/tasks/{taskId}/reviews/{reviewId}")
public class SecurityPolicyComplianceController {

    private final SecurityPolicyComplianceService securityPolicyComplianceService;

    public SecurityPolicyComplianceController(SecurityPolicyComplianceService securityPolicyComplianceService) {
        this.securityPolicyComplianceService = securityPolicyComplianceService;
    }

    @GetMapping("/policy-compliance")
    public ResponseEntity<SecurityPolicyComplianceDto> evaluatePolicyCompliance(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId,
            @PathVariable UUID taskId,
            @PathVariable UUID reviewId,
            @AuthenticationPrincipal UserPrincipal currentUserPrincipal) {

        if (currentUserPrincipal == null || currentUserPrincipal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required");
        }

        SecurityPolicyComplianceDto response = securityPolicyComplianceService.evaluatePolicyCompliance(
                projectId, repositoryId, taskId, reviewId, currentUserPrincipal.getUser());

        return ResponseEntity.ok(response);
    }
}
