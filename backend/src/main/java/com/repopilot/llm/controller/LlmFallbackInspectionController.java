package com.repopilot.llm.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.service.LlmFallbackInspectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/llm/requests")
public class LlmFallbackInspectionController {

    private final LlmFallbackInspectionService fallbackInspectionService;

    @Autowired
    public LlmFallbackInspectionController(LlmFallbackInspectionService fallbackInspectionService) {
        this.fallbackInspectionService = fallbackInspectionService;
    }

    @GetMapping("/{logicalRequestId}")
    public ResponseEntity<LogicalRequestInspectionResponse> inspectLogicalRequest(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("logicalRequestId") UUID logicalRequestId) {
        requireAuthentication(principal);
        LogicalRequestInspectionResponse response = fallbackInspectionService.getLogicalRequest(orgId, logicalRequestId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{logicalRequestId}/attempts")
    public ResponseEntity<PagedPolicyResponse<ProviderAttemptInspectionResponse>> listAttempts(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("logicalRequestId") UUID logicalRequestId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        requireAuthentication(principal);
        PagedPolicyResponse<ProviderAttemptInspectionResponse> response = fallbackInspectionService.getAttempts(orgId, logicalRequestId, page, size, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{logicalRequestId}/fallback-decisions")
    public ResponseEntity<PagedPolicyResponse<FallbackDecisionInspectionResponse>> listFallbackDecisions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("logicalRequestId") UUID logicalRequestId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        requireAuthentication(principal);
        PagedPolicyResponse<FallbackDecisionInspectionResponse> response = fallbackInspectionService.getFallbackDecisions(orgId, logicalRequestId, page, size, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{logicalRequestId}/execution-tree")
    public ResponseEntity<LlmExecutionTreeResponse> inspectExecutionTree(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("logicalRequestId") UUID logicalRequestId) {
        requireAuthentication(principal);
        LlmExecutionTreeResponse response = fallbackInspectionService.getExecutionTree(orgId, logicalRequestId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    private void requireAuthentication(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
    }
}
