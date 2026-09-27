package com.repopilot.llm.controller;

import com.repopilot.auth.security.UserPrincipal;
import com.repopilot.common.exception.ApiException;
import com.repopilot.llm.dto.ProviderCapabilityResponse;
import com.repopilot.llm.dto.ProviderHealthResponse;
import com.repopilot.llm.service.LlmProviderInspectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/llm/providers")
public class LlmProviderInspectionController {

    private final LlmProviderInspectionService providerInspectionService;

    @Autowired
    public LlmProviderInspectionController(LlmProviderInspectionService providerInspectionService) {
        this.providerInspectionService = providerInspectionService;
    }

    @GetMapping
    public ResponseEntity<List<ProviderCapabilityResponse>> listProviders(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId) {
        requireAuthentication(principal);
        List<ProviderCapabilityResponse> response = providerInspectionService.getRegisteredProviders(orgId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{providerId}")
    public ResponseEntity<ProviderCapabilityResponse> getProviderCapability(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("providerId") String providerId) {
        requireAuthentication(principal);
        ProviderCapabilityResponse response = providerInspectionService.getProviderCapability(orgId, providerId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{providerId}/health")
    public ResponseEntity<ProviderHealthResponse> getProviderHealth(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("orgId") UUID orgId,
            @PathVariable("providerId") String providerId) {
        requireAuthentication(principal);
        ProviderHealthResponse response = providerInspectionService.getProviderHealth(orgId, providerId, principal.getUser());
        return ResponseEntity.ok(response);
    }

    private void requireAuthentication(UserPrincipal principal) {
        if (principal == null || principal.getUser() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
    }
}
