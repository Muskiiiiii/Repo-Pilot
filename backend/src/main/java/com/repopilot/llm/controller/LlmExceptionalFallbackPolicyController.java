package com.repopilot.llm.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.*;
import com.repopilot.llm.service.LlmPolicyAdministrationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/llm/policies/exceptional")
public class LlmExceptionalFallbackPolicyController {

    private final LlmPolicyAdministrationService policyAdministrationService;

    @Autowired
    public LlmExceptionalFallbackPolicyController(LlmPolicyAdministrationService policyAdministrationService) {
        this.policyAdministrationService = policyAdministrationService;
    }

    @PostMapping
    public ResponseEntity<ExceptionalPolicyResponse> createPolicy(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @Valid @RequestBody CreateExceptionalPolicyRequest request) {
        requireAuthentication(principal);
        ExceptionalPolicyResponse response = policyAdministrationService.createExceptionalPolicyVersion(orgId, principal.getUser(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PagedPolicyResponse<ExceptionalPolicyResponse>> listPolicies(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        requireAuthentication(principal);
        PagedPolicyResponse<ExceptionalPolicyResponse> response = policyAdministrationService.getExceptionalPolicyVersions(orgId, principal.getUser(), page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{policyId}")
    public ResponseEntity<ExceptionalPolicyResponse> getPolicyVersion(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("policyId") UUID policyId) {
        requireAuthentication(principal);
        ExceptionalPolicyResponse response = policyAdministrationService.getSpecificPolicyVersion(orgId, policyId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/active")
    public ResponseEntity<ActiveExceptionalPolicyResponse> getActivePointer(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @RequestParam("sourceAuthorityType") String sourceAuthorityType,
            @RequestParam("originalProviderId") String originalProviderId,
            @RequestParam("targetProviderId") String targetProviderId) {
        requireAuthentication(principal);
        ActiveExceptionalPolicyResponse response = policyAdministrationService.getActivePointer(
                orgId, sourceAuthorityType, originalProviderId, targetProviderId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{policyId}/activate")
    public ResponseEntity<ActiveExceptionalPolicyResponse> activatePolicy(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("policyId") UUID policyId) {
        requireAuthentication(principal);
        ActiveExceptionalPolicyResponse response = policyAdministrationService.activatePolicyVersion(orgId, policyId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/active")
    public ResponseEntity<Void> deactivateActivePolicy(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @RequestParam("sourceAuthorityType") String sourceAuthorityType,
            @RequestParam("originalProviderId") String originalProviderId,
            @RequestParam("targetProviderId") String targetProviderId) {
        requireAuthentication(principal);
        policyAdministrationService.deactivateActivePolicy(
                orgId, sourceAuthorityType, originalProviderId, targetProviderId, principal.getUser());
        return ResponseEntity.noContent().build();
    }

    private void requireAuthentication(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
    }
}
